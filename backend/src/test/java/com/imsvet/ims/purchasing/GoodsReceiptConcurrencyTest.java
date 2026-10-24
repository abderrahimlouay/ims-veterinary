package com.imsvet.ims.purchasing;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.purchasing.application.GoodsReceipts;
import com.imsvet.ims.purchasing.application.GoodsReceipts.ReceiptInput;
import com.imsvet.ims.purchasing.application.GoodsReceipts.ReceivedLine;
import com.imsvet.ims.purchasing.application.PurchaseOrders;
import com.imsvet.ims.purchasing.application.PurchaseOrders.OrderInput;
import com.imsvet.ims.purchasing.application.PurchaseOrders.RequestedLine;
import com.imsvet.ims.support.StockAssertions;
import com.imsvet.ims.support.TestActor;
import com.imsvet.ims.support.TestLots;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * Parallel receipts on committed data, 20 times each. CT-08: two receipts of the same new lot number give one lot
 * with the summed balance and the weighted-average cost, ledger = balance. Two receipts that together exceed what
 * is outstanding on one order line: one is refused with {@code RECEIPT_QUANTITY_EXCEEDS_ORDER} (BR-PURCH-004).
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class GoodsReceiptConcurrencyTest {

    private static final LocalDate EXPIRY = LocalDate.of(2027, 6, 30);

    @Autowired
    private GoodsReceipts receipts;

    @Autowired
    private PurchaseOrders orders;

    @Autowired
    private JdbcTemplate jdbc;

    private String key;
    private long operator;
    private long supplier;
    private long product;

    @BeforeEach
    void data() {
        TestLots lots = new TestLots(jdbc);
        key = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO suppliers (name, created_at, updated_at) VALUES (?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                "Parallel " + key);
        supplier = jdbc.queryForObject("SELECT id FROM suppliers WHERE name = ?", Long.class, "Parallel " + key);
        product = lots.product("PAR-" + key, "Parallel receipt");
        operator = lots.fixtureUser();
    }

    @AfterEach
    void signOut() {
        TestActor.signOut();
    }

    @RepeatedTest(20)
    void twoReceiptsOfOneNewLotGiveOneLotWithTheSummedBalanceAndTheAverageCost() throws Exception {
        String lotNumber = "CT8-" + key;
        List<Object> outcomes = race(
                new ReceiptInput(supplier, null, null, null, false, List.of(line(null, lotNumber, "7", "0.3100"))),
                new ReceiptInput(supplier, null, null, null, false, List.of(line(null, lotNumber, "5", "0.4500"))));

        assertThat(outcomes).allSatisfy(o -> assertThat(o).isNull());
        List<Long> lots = jdbc.queryForList("SELECT id FROM lots WHERE product_id = ?", Long.class, product);
        assertThat(lots).hasSize(1);
        // (7 × 0.3100 + 5 × 0.4500) / 12 = 0.36833… → 0.3683, whichever receipt came first
        assertThat(jdbc.queryForMap("""
                SELECT CAST(quantity_on_hand AS CHAR) AS qty, CAST(unit_cost AS CHAR) AS cost FROM lots WHERE id = ?""",
                lots.getFirst())).containsEntry("qty", "12.000").containsEntry("cost", "0.3683");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM goods_receipts WHERE supplier_id = ?", Integer.class,
                supplier)).isEqualTo(2);
        StockAssertions.assertLedgerMatchesBalances(jdbc, lots);
    }

    @RepeatedTest(20)
    void twoReceiptsTogetherBeyondTheOutstandingQuantityLeaveOneRefused() throws Exception {
        TestActor.signIn(operator, "purchase.create", "purchase.validate");
        long order = orders.create(new OrderInput(supplier, null, List.of(new RequestedLine(product, null,
                new BigDecimal("10"), new BigDecimal("0.5"), null, null)))).id();
        orders.validate(order);
        long poLine = orders.send(order).lines().getFirst().id();

        List<Object> outcomes = race(
                new ReceiptInput(null, order, null, null, false, List.of(line(poLine, "A-" + key, "6", "0.5"))),
                new ReceiptInput(null, order, null, null, false, List.of(line(poLine, "B-" + key, "6", "0.5"))));

        assertThat(outcomes).filteredOn(o -> o == null).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o != null).singleElement()
                .isEqualTo(ErrorCode.RECEIPT_QUANTITY_EXCEEDS_ORDER);
        assertThat(jdbc.queryForMap("""
                SELECT o.status, CAST(l.received_base AS CHAR) AS received FROM purchase_orders o
                JOIN purchase_order_lines l ON l.purchase_order_id = o.id WHERE o.id = ?""", order))
                .containsEntry("status", "partially_received").containsEntry("received", "6.000");
        StockAssertions.assertLedgerMatchesBalances(jdbc,
                jdbc.queryForList("SELECT id FROM lots WHERE product_id = ?", Long.class, product));
    }

    /** Runs both receipts at once; per receipt, {@code null} when confirmed, else the refusal's code. */
    private List<Object> race(ReceiptInput first, ReceiptInput second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (ReceiptInput input : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    TestActor.signIn(operator, "receipt.create");
                    try {
                        start.await();
                        receipts.confirm(input, null);
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

    private ReceivedLine line(Long poLine, String lotNumber, String quantity, String cost) {
        return new ReceivedLine(poLine, product, null, new BigDecimal(quantity), lotNumber, EXPIRY,
                new BigDecimal(cost), true);
    }
}
