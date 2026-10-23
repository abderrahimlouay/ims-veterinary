package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.inventory.application.CountRegularisation;
import com.imsvet.ims.inventory.application.InventoryCounts;
import com.imsvet.ims.inventory.application.InventoryCounts.OpenInput;
import com.imsvet.ims.inventory.application.StockAdjustments;
import com.imsvet.ims.inventory.application.StockAdjustments.AdjustmentInput;
import com.imsvet.ims.support.StockAssertions;
import com.imsvet.ims.support.TestActor;
import com.imsvet.ims.support.TestLots;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Concurrency of regularisation, repeated 20 times on committed data: CT-04 (testing.md §6) — regularisation racing
 * a withdrawal on the same lot keeps both changes (no lost update, ledger = balance, ADR-072); and two simultaneous
 * regularisations of one count post its movements once (BR-INV-004).
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class CountRegularisationConcurrencyTest {

    private static final String[] PERMISSIONS = { "inventory.count", "inventory.validate", "stock.adjust" };

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InventoryCounts counts;

    @Autowired
    private CountRegularisation regularisation;

    @Autowired
    private StockAdjustments adjustments;

    private long operator;
    private long lot;
    private long count;

    /** A lot of 10, counted 8 at balance 10: variance −2. */
    @BeforeEach
    void countedLot() {
        TestLots lots = new TestLots(jdbc);
        String key = UUID.randomUUID().toString().substring(0, 8);
        long product = lots.product("CT04-" + key, "Counted concurrently");
        lot = lots.lot(product, "L-" + key, LocalDate.of(2027, 5, 31), "10");
        operator = lots.fixtureUser();
        TestActor.signIn(operator, PERMISSIONS);
        count = counts.open(new OpenInput("cycle", null, List.of(product), null)).id();
        long line = jdbc.queryForObject("SELECT id FROM inventory_lines WHERE inventory_id = ?", Long.class, count);
        counts.enter(count, line, new BigDecimal("8"));
    }

    @AfterEach
    void signOut() {
        TestActor.signOut();
    }

    @RepeatedTest(20)
    void ct04RegularisationRacingAWithdrawalLosesNoUpdate() throws Exception {
        List<String> outcomes = race(
                () -> regularisation.regularize(count, null),
                () -> adjustments.adjust(new AdjustmentInput(lot, "adjustment_breakage", new BigDecimal("3"),
                        "Broken")));

        assertThat(outcomes).containsOnly("ok");
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", String.class, lot))
                .isEqualTo("5.000");
        assertThat(inventoryMovements()).containsExactly(new BigDecimal("-2.000"));
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
    }

    @RepeatedTest(20)
    void twoSimultaneousRegularisationsPostOnce() throws Exception {
        List<String> outcomes = race(() -> regularisation.regularize(count, null),
                () -> regularisation.regularize(count, null));

        assertThat(outcomes).containsExactlyInAnyOrder("ok", "INVENTORY_NOT_OPEN");
        assertThat(inventoryMovements()).containsExactly(new BigDecimal("-2.000"));
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", String.class, lot))
                .isEqualTo("8.000");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE action = 'STOCK_CORRECTION' AND entity = 'lots' AND entity_id = ?""",
                Integer.class, lot)).isEqualTo(1);
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
    }

    private List<String> race(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<String>> results = new ArrayList<>();
        for (Callable<?> task : List.of(first, second)) {
            results.add(pool.submit(() -> {
                TestActor.signIn(operator, PERMISSIONS);
                try {
                    start.await();
                    task.call();
                    return "ok";
                } catch (BusinessException e) {
                    return e.code().name();
                } finally {
                    TestActor.signOut();
                }
            }));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> result : results) {
            outcomes.add(result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    private List<BigDecimal> inventoryMovements() {
        return jdbc.queryForList("""
                SELECT quantity FROM stock_movements WHERE lot_id = ? AND movement_type = 'adjustment_inventory'""",
                BigDecimal.class, lot);
    }
}
