package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.inventory.application.CountRegularisation;
import com.imsvet.ims.inventory.application.InventoryCounts;
import com.imsvet.ims.inventory.application.InventoryCounts.OpenInput;
import com.imsvet.ims.sales.application.PaymentRecorder.Tender;
import com.imsvet.ims.sales.application.SaleConfirmation;
import com.imsvet.ims.sales.application.SaleConversion;
import com.imsvet.ims.sales.application.SaleLines;
import com.imsvet.ims.sales.application.SaleLines.LineInput;
import com.imsvet.ims.sales.application.SalesDocuments;
import com.imsvet.ims.sales.application.SalesDocuments.DraftInput;
import com.imsvet.ims.sales.application.SalesViews.SaleDetail;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real sales on committed data (testing.md §6): CT-01 last units, CT-02 opposite lot order, CT-04 sale against a count
 * regularisation, CT-05 double submit, CT-06 credit sales near the limit, a second confirmation in parallel; and atomicity (BR-SALE-015): a failure in
 * the last step leaves no number, movement, payment or status change.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class SaleConfirmationConcurrencyTest {

    private static final String[] PERMISSIONS = { "sale.create", "inventory.count", "inventory.validate" };
    private static final LocalDate EXPIRY = LocalDate.of(2099, 5, 31);

    @Autowired
    private SalesDocuments documents;

    @Autowired
    private SaleLines lines;

    @Autowired
    private SaleConfirmation confirmation;

    @Autowired
    private SaleConversion conversion;

    @Autowired
    private InventoryCounts counts;

    @Autowired
    private CountRegularisation regularisation;

    @Autowired
    private JdbcTemplate jdbc;

    private TestLots fixtures;
    private long operator;

    @BeforeEach
    void signIn() {
        fixtures = new TestLots(jdbc);
        operator = fixtures.fixtureUser();
        TestActor.signIn(operator, PERMISSIONS);
    }

    @AfterEach
    void signOut() {
        TestActor.signOut();
    }

    @RepeatedTest(5)
    void ct01ParallelSalesOfTheLastUnitsNeverGoNegative() throws Exception {
        long product = product();
        long lot = fixtures.lot(product, "L-" + key(), EXPIRY, "5");
        List<Callable<?>> sales = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            SaleDetail draft = draft(product);
            sales.add(() -> confirmation.confirm(draft.id(), paid(draft), null));
        }
        List<String> outcomes = race(sales);

        assertThat(outcomes).filteredOn("ok"::equals).hasSize(5);
        assertThat(outcomes).filteredOn("STOCK_INSUFFICIENT"::equals).hasSize(5);
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", BigDecimal.class, lot))
                .isEqualByComparingTo("0");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
    }

    @RepeatedTest(5)
    void ct02OppositeLotOrdersDoNotDeadlock() throws Exception {
        long a = product();
        long b = product();
        long lotA = fixtures.lot(a, "A-" + key(), EXPIRY, "10");
        long lotB = fixtures.lot(b, "B-" + key(), EXPIRY, "10");
        SaleDetail first = draft(a, b);
        SaleDetail second = draft(b, a);
        List<String> outcomes = race(List.of(() -> confirmation.confirm(first.id(), paid(first), null),
                () -> confirmation.confirm(second.id(), paid(second), null)));

        assertThat(outcomes).containsOnly("ok");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lotA, lotB));
    }

    @RepeatedTest(5)
    void ct04ASaleRacingARegularisationLosesNoUpdate() throws Exception {
        long product = product();
        long lot = fixtures.lot(product, "L-" + key(), EXPIRY, "10");
        long count = counts.open(new OpenInput("cycle", null, List.of(product), null)).id();
        long line = jdbc.queryForObject("SELECT id FROM inventory_lines WHERE inventory_id = ?", Long.class, count);
        counts.enter(count, line, new BigDecimal("8"));
        SaleDetail draft = draft(product);
        List<String> outcomes = race(List.of(() -> regularisation.regularize(count, null),
                () -> confirmation.confirm(draft.id(), paid(draft), null)));

        assertThat(outcomes).containsOnly("ok");
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", BigDecimal.class, lot))
                .isEqualByComparingTo("7");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
    }

    @RepeatedTest(5)
    void ct05ADoubleSubmitWithOneKeyMakesOneSale() throws Exception {
        long product = product();
        long lot = fixtures.lot(product, "L-" + key(), EXPIRY, "10");
        SaleDetail draft = draft(product);
        String key = UUID.randomUUID().toString();
        List<String> outcomes = race(List.of(() -> confirmation.confirm(draft.id(), paid(draft), key),
                () -> confirmation.confirm(draft.id(), paid(draft), key)));

        assertThat(outcomes).containsOnly("ok");
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", BigDecimal.class, lot))
                .isEqualByComparingTo("9");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_allocations WHERE invoice_id = ?", Integer.class,
                draft.id())).isOne();
    }

    @RepeatedTest(5)
    void twoConfirmationsWithoutAKeyPostOnce() throws Exception {
        long product = product();
        long lot = fixtures.lot(product, "L-" + key(), EXPIRY, "10");
        SaleDetail draft = draft(product);
        List<String> outcomes = race(List.of(() -> confirmation.confirm(draft.id(), paid(draft), null),
                () -> confirmation.confirm(draft.id(), paid(draft), null)));

        assertThat(outcomes).containsExactlyInAnyOrder("ok", "INVALID_STATE_TRANSITION");
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", BigDecimal.class, lot))
                .isEqualByComparingTo("9");
    }

    @RepeatedTest(5)
    void ct06ParallelCreditSalesNeverExceedTheLimit() throws Exception {
        long product = product();
        long lot = fixtures.lot(product, "L-" + key(), EXPIRY, "100");
        jdbc.update("""
                INSERT INTO customers (name, credit_limit, created_at, updated_at)
                VALUES (?, 32.70, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", "Ferme " + key());
        long customer = jdbc.queryForObject("SELECT MAX(id) FROM customers", Long.class);
        List<Callable<?>> sales = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            SaleDetail draft = documents.create(new DraftInput("invoice", customer, true, null, null));
            lines.add(draft.id(), new LineInput(product, null, BigDecimal.ONE, null, null, null, null, null));
            sales.add(() -> confirmation.confirm(draft.id(), List.of(), null));
        }
        List<String> outcomes = race(sales);

        assertThat(outcomes).filteredOn("ok"::equals).hasSize(3);
        assertThat(outcomes).filteredOn("CREDIT_LIMIT_EXCEEDED"::equals).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT SUM(total_ttc) FROM sales_documents WHERE customer_id = ?"
                + " AND status = 'confirmed'", BigDecimal.class, customer)).isEqualByComparingTo("32.70");
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", BigDecimal.class, lot))
                .isEqualByComparingTo("97");
    }

    @RepeatedTest(5)
    void twoConversionsOfADeliveryNoteGiveOneInvoice() throws Exception {
        long product = product();
        fixtures.lot(product, "L-" + key(), EXPIRY, "10");
        jdbc.update("""
                INSERT INTO customers (name, created_at, updated_at)
                VALUES (?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", "Ferme " + key());
        long customer = jdbc.queryForObject("SELECT MAX(id) FROM customers", Long.class);
        SaleDetail note = documents.create(new DraftInput("delivery_note", customer, false, null, null));
        lines.add(note.id(), new LineInput(product, null, BigDecimal.ONE, null, null, null, null, null));
        confirmation.confirm(note.id(), List.of(), null);
        List<String> outcomes = race(List.of(() -> conversion.convert(note.id(), "invoice"),
                () -> conversion.convert(note.id(), "invoice")));

        assertThat(outcomes).containsExactlyInAnyOrder("ok", "INVALID_STATE_TRANSITION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_documents WHERE parent_id = ?", Integer.class,
                note.id())).isOne();
    }

    @Test
    void aFailureInTheLastStepLeavesNothingAndConsumesNoNumber() {
        long product = product();
        long lot = fixtures.lot(product, "L-" + key(), EXPIRY, "10");
        SaleDetail first = draft(product);
        String number = confirmation.confirm(first.id(), paid(first), null).number();

        SaleDetail failing = draft(product);
        List<Tender> cash = List.of(new Tender("cash", failing.totals().ttc(), null));
        jdbc.update("UPDATE cash_sessions SET status = 'closed' WHERE cashier_id = ?", operator);
        try {
            confirmation.confirm(failing.id(), cash, null);
            throw new AssertionError("A cash part without a session must fail");
        } catch (BusinessException expected) {
            assertThat(expected.code().name()).isEqualTo("CASH_SESSION_REQUIRED");
        }
        assertThat(jdbc.queryForObject("SELECT status FROM sales_documents WHERE id = ?", String.class, failing.id()))
                .isEqualTo("draft");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE reference_type = 'sales_document'"
                + " AND reference_id = ?", Integer.class, failing.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", BigDecimal.class, lot))
                .isEqualByComparingTo("9");

        String next = confirmation.confirm(failing.id(), paid(failing), null).number();
        long previous = Long.parseLong(number.substring(number.lastIndexOf('-') + 1));
        assertThat(Long.parseLong(next.substring(next.lastIndexOf('-') + 1))).isEqualTo(previous + 1);
    }

    private long product() {
        long product = fixtures.product("CF-" + key(), "Vendu en parallèle");
        jdbc.update("UPDATE products SET sale_price = 10 WHERE id = ?", product);
        return product;
    }

    private SaleDetail draft(long... products) {
        SaleDetail detail = documents.create(new DraftInput("receipt", null, null, null, null));
        for (long product : products) {
            detail = lines.add(detail.id(), new LineInput(product, null, BigDecimal.ONE, null, null, null, null, null));
        }
        return detail;
    }

    private static List<Tender> paid(SaleDetail draft) {
        return List.of(new Tender("cheque", draft.totals().ttc(), "CHQ-1"));
    }

    private static String key() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private List<String> race(List<Callable<?>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(pool.submit(() -> {
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
            for (Future<String> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
