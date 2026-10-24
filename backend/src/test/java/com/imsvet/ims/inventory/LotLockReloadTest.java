package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.inventory.application.StockEntries;
import com.imsvet.ims.inventory.application.StockEntries.Batch;
import com.imsvet.ims.inventory.application.StockEntries.Entry;
import com.imsvet.ims.inventory.application.StockService;
import com.imsvet.ims.inventory.application.StockService.Movement;
import com.imsvet.ims.inventory.domain.MovementType;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import com.imsvet.ims.support.StockAssertions;
import com.imsvet.ims.support.TestActor;
import com.imsvet.ims.support.TestLots;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BR-STOCK-001/002 and ADR-012 on committed data: a lot read earlier in a transaction (to validate a request) is
 * reloaded when it is locked, so a movement committed by another transaction in between is never lost — neither by
 * {@code StockService} nor by a stock entry found by its identity (weighted average on the current balance).
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class LotLockReloadTest {

    private static final LocalDate EXPIRY = LocalDate.of(2027, 5, 31);

    @Autowired
    private StockService stock;

    @Autowired
    private StockEntries entries;

    @Autowired
    private LotRepository lots;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private long operator;
    private long product;
    private long lot;
    private String lotNumber;

    @BeforeEach
    void lot() {
        TestLots testLots = new TestLots(jdbc);
        String key = UUID.randomUUID().toString().substring(0, 8);
        product = testLots.product("RELOAD-" + key, "Reload");
        lotNumber = "L-" + key;
        lot = testLots.lot(product, lotNumber, EXPIRY, "10");
        jdbc.update("UPDATE lots SET unit_cost = 0.3000 WHERE id = ?", lot);
        operator = testLots.fixtureUser();
    }

    @Test
    void aLotReadBeforeAnotherTransactionsWithdrawalIsReloadedWhenLocked() throws Exception {
        runAfterAConcurrentWithdrawal(() -> lots.findById(lot).orElseThrow(), () -> stock.post(List.of(
                new Movement(lot, product, MovementType.ADJUSTMENT_BREAKAGE, new BigDecimal("-6"), "Broken", null,
                        null))));

        assertThat(balanceAndCost()).containsExactly("2.000", "0.3000");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
    }

    @Test
    void aLotFoundByIdentityBeforeAnotherTransactionsWithdrawalIsReloadedForTheEntry() throws Exception {
        runAfterAConcurrentWithdrawal(() -> lots.findByIdentity(product, lotNumber).orElseThrow(),
                () -> entries.enter(new Batch(MovementType.OPENING_STOCK, "opening_stock", null, null, null, false,
                        null), List.of(new Entry(product, lotNumber, EXPIRY, new BigDecimal("4"),
                                new BigDecimal("0.4200"), false))));

        // (8 × 0.3000 + 4 × 0.4200) / 12 = 0.3400, on the balance after the withdrawal
        assertThat(balanceAndCost()).containsExactly("12.000", "0.3400");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
    }

    /** In one transaction: {@code read}, then wait for another transaction to withdraw 2 and commit, then {@code write}. */
    private void runAfterAConcurrentWithdrawal(Runnable read, Runnable write) throws Exception {
        CountDownLatch readDone = new CountDownLatch(1);
        CountDownLatch withdrawn = new CountDownLatch(1);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
            TestActor.signIn(operator);
            try {
                transactions.executeWithoutResult(status -> {
                    read.run();
                    readDone.countDown();
                    await(withdrawn);
                    write.run();
                });
            } finally {
                TestActor.signOut();
            }
        });
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> {
            await(readDone);
            TestActor.signIn(operator);
            try {
                transactions.executeWithoutResult(status -> stock.post(List.of(new Movement(lot, product,
                        MovementType.ADJUSTMENT_BREAKAGE, new BigDecimal("-2"), "Dropped", null, null))));
            } finally {
                TestActor.signOut();
                withdrawn.countDown();
            }
        });
        second.get(30, TimeUnit.SECONDS);
        first.get(30, TimeUnit.SECONDS);
    }

    private List<String> balanceAndCost() {
        return jdbc.queryForObject("""
                SELECT CAST(quantity_on_hand AS CHAR) AS qty, CAST(unit_cost AS CHAR) AS cost FROM lots WHERE id = ?""",
                (rs, n) -> List.of(rs.getString("qty"), rs.getString("cost")), lot);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
