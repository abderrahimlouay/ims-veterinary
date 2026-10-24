package com.imsvet.ims.purchasing;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.inventory.application.StockAdjustments;
import com.imsvet.ims.inventory.application.StockAdjustments.AdjustmentInput;
import com.imsvet.ims.purchasing.application.GoodsReceipts;
import com.imsvet.ims.purchasing.application.GoodsReceipts.ReceiptInput;
import com.imsvet.ims.purchasing.application.GoodsReceipts.ReceivedLine;
import com.imsvet.ims.purchasing.application.SupplierReturns;
import com.imsvet.ims.purchasing.application.SupplierReturns.ReturnInput;
import com.imsvet.ims.purchasing.application.SupplierReturns.ReturnedLine;
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
 * Receipt cancellations racing on committed data, 20 times each (BR-PURCH-012, ADR-103): two cancellations of one
 * receipt — one cancels, the other is refused as already cancelled; a cancellation and a withdrawal from the same
 * lot — whichever goes first, the ledger equals the balance, no balance is negative, and either the receipt is
 * cancelled (the withdrawal then finding no stock) or it is refused as no longer cancellable; the same with a
 * supplier return of that lot, which also checks that the lock-order exception never deadlocks.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class GoodsReceiptCancellationConcurrencyTest {

    @Autowired
    private GoodsReceipts receipts;

    @Autowired
    private StockAdjustments adjustments;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SupplierReturns returns;

    private long operator;
    private long supplier;
    private long receipt;
    private long lot;

    @BeforeEach
    void receipt() {
        TestLots lots = new TestLots(jdbc);
        String key = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO suppliers (name, created_at, updated_at) VALUES (?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                "Race " + key);
        supplier = jdbc.queryForObject("SELECT id FROM suppliers WHERE name = ?", Long.class, "Race " + key);
        long product = lots.product("RACE-" + key, "Cancel race");
        operator = lots.fixtureUser();
        TestActor.signIn(operator, "receipt.create");
        receipt = receipts.confirm(new ReceiptInput(supplier, null, null, null, false, List.of(new ReceivedLine(null,
                product, null, new BigDecimal("5"), "L-" + key, LocalDate.of(2027, 6, 30), new BigDecimal("0.4"),
                true))), null).id();
        lot = jdbc.queryForObject("SELECT lot_id FROM goods_receipt_lines WHERE receipt_id = ?", Long.class, receipt);
        TestActor.signOut();
    }

    @AfterEach
    void signOut() {
        TestActor.signOut();
    }

    @RepeatedTest(20)
    void twoCancellationsOfOneReceiptCancelItOnce() throws Exception {
        List<Object> outcomes = race(() -> receipts.cancel(receipt, "First"), () -> receipts.cancel(receipt, "Second"));

        assertThat(outcomes).filteredOn(o -> o == null).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o != null).containsExactly(ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM stock_movements WHERE lot_id = ? AND movement_type = 'receipt_cancellation'""",
                Integer.class, lot)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT CAST(quantity_on_hand AS CHAR) FROM lots WHERE id = ?", String.class,
                lot)).isEqualTo("0.000");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
    }

    @RepeatedTest(20)
    void aCancellationRacingAWithdrawalLeavesAConsistentLedger() throws Exception {
        List<Object> outcomes = race(() -> receipts.cancel(receipt, "Wrong delivery"),
                () -> adjustments.adjust(new AdjustmentInput(lot, "adjustment_breakage", new BigDecimal("2"), "Dropped")));

        String status = jdbc.queryForObject("SELECT status FROM goods_receipts WHERE id = ?", String.class, receipt);
        if (status.equals("cancelled")) {
            assertThat(outcomes).containsExactly(null, ErrorCode.STOCK_INSUFFICIENT);
        } else {
            assertThat(outcomes).containsExactly(ErrorCode.RECEIPT_NOT_CANCELLABLE, null);
        }
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", BigDecimal.class, lot))
                .isNotNegative();
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
    }

    /**
     * The lock-order exception of database.md §7 (a cancellation locks its receipt row, then the lots) against a
     * supplier return (SR counter row, then the lots) on the same lot: never a deadlock; either the receipt is
     * cancelled and the return finds no stock, or the return goes first and the receipt is no longer cancellable.
     */
    @RepeatedTest(20)
    void aCancellationRacingASupplierReturnOfTheSameLotNeverDeadlocks() throws Exception {
        List<Object> outcomes = race(() -> receipts.cancel(receipt, "Wrong delivery"),
                () -> returns.create(new ReturnInput(supplier, "Damaged", List.of(new ReturnedLine(lot,
                        new BigDecimal("2"), null)))));

        String status = jdbc.queryForObject("SELECT status FROM goods_receipts WHERE id = ?", String.class, receipt);
        if (status.equals("cancelled")) {
            assertThat(outcomes).containsExactly(null, ErrorCode.STOCK_INSUFFICIENT);
        } else {
            assertThat(outcomes).containsExactly(ErrorCode.RECEIPT_NOT_CANCELLABLE, null);
        }
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
    }

    /** Runs both at once; per task, {@code null} when it succeeded, else the refusal's code. */
    private List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    TestActor.signIn(operator, "receipt.cancel", "stock.adjust", "return.supplier.create");
                    try {
                        start.await();
                        task.call();
                        return null;
                    } catch (BusinessException e) {
                        return e.code();
                    } finally {
                        TestActor.signOut();
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
