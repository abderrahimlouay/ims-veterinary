package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.sales.application.CustomerReturns;
import com.imsvet.ims.sales.application.CustomerReturns.ReturnInput;
import com.imsvet.ims.sales.application.CustomerReturns.ReturnLineInput;
import com.imsvet.ims.sales.application.PaymentRecorder.Tender;
import com.imsvet.ims.sales.application.SaleCancellation;
import com.imsvet.ims.sales.application.SaleConfirmation;
import com.imsvet.ims.sales.application.SaleLines;
import com.imsvet.ims.sales.application.SaleLines.LineInput;
import com.imsvet.ims.sales.application.SalesDocuments;
import com.imsvet.ims.sales.application.SalesDocuments.DraftInput;
import com.imsvet.ims.sales.application.SalesViews.SaleDetail;
import com.imsvet.ims.support.MigrationAccount;
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
 * Customer returns on committed data (C, BR-RET-001, BR-RET-005, ADR-130): parallel returns of the same sale line
 * serialise on the original document's lock, so the returned quantity never exceeds the sold one; a return racing the
 * cancellation of its document leaves exactly one of them; a failure in the last write (the {@code RETURN_CREATE}
 * audit) leaves no return, credit note, movement, refund, returned quantity or number consumed.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class CustomerReturnConcurrencyTest {

    private static final String[] PERMISSIONS = { "sale.create", "sale.cancel", "return.customer.create" };
    private static final LocalDate EXPIRY = LocalDate.of(2099, 5, 31);
    /** One unit at 10.00 HT with the fixture product's 9 % VAT. */
    private static final BigDecimal UNIT_TTC = new BigDecimal("10.90");

    @Autowired
    private SalesDocuments documents;

    @Autowired
    private SaleLines lines;

    @Autowired
    private SaleConfirmation confirmation;

    @Autowired
    private SaleCancellation cancellation;

    @Autowired
    private CustomerReturns returns;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MigrationAccount migration;

    private TestLots fixtures;
    private long operator;
    private long product;
    private long lot;

    @BeforeEach
    void signIn() {
        fixtures = new TestLots(jdbc);
        operator = fixtures.fixtureUser();
        TestActor.signIn(operator, PERMISSIONS);
        product = fixtures.product("CRC-" + key(), "Concurrent return");
        jdbc.update("UPDATE products SET sale_price = 10 WHERE id = ?", product);
        lot = fixtures.lot(product, "L-" + key(), EXPIRY, "10");
    }

    @AfterEach
    void signOut() {
        TestActor.signOut();
    }

    @RepeatedTest(5)
    void parallelReturnsOfTheSameLineNeverExceedTheSoldQuantity() throws Exception {
        long receipt = soldReceipt("3");
        long saleLine = saleLine(receipt);
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> returns.create(input(receipt, saleLine, "2"), null));
        }

        List<String> outcomes = race(tasks);

        assertThat(outcomes).filteredOn("ok"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("RETURN_QUANTITY_EXCEEDED"::equals).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT returned_quantity_base FROM sales_document_lines WHERE id = ?",
                BigDecimal.class, saleLine)).isEqualByComparingTo("2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_returns WHERE document_id = ?", Integer.class,
                receipt)).isOne();
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", BigDecimal.class, lot))
                .isEqualByComparingTo("9");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
    }

    @RepeatedTest(5)
    void aReturnRacingTheCancellationOfItsDocumentLeavesExactlyOne() throws Exception {
        long receipt = soldReceipt("2");
        long saleLine = saleLine(receipt);

        List<String> outcomes = race(List.of(() -> returns.create(input(receipt, saleLine, "1"), null),
                () -> cancellation.cancel(receipt, "Erreur de caisse")));

        assertThat(outcomes).filteredOn("ok"::equals).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !"ok".equals(o)).singleElement()
                .isIn("SALE_NOT_CANCELLABLE", "INVALID_STATE_TRANSITION");
        boolean returned = jdbc.queryForObject("SELECT COUNT(*) FROM customer_returns WHERE document_id = ?",
                Integer.class, receipt) == 1;
        String status = jdbc.queryForObject("SELECT status FROM sales_documents WHERE id = ?", String.class, receipt);
        assertThat(status).isEqualTo(returned ? "confirmed" : "cancelled");
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", BigDecimal.class, lot))
                .isEqualByComparingTo(returned ? "9" : "10");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
    }

    @Test
    void aFailureInTheLastWriteLeavesNothingAndConsumesNoNumber() {
        long receipt = soldReceipt("2");
        long saleLine = saleLine(receipt);
        int crBefore = counter("CR");
        int cnBefore = counter("CN");
        long movementsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE lot_id = ?", Long.class,
                lot);
        migration.jdbc().execute("""
                CREATE TRIGGER trg_test_audit_log_refuse_return BEFORE INSERT ON audit_log FOR EACH ROW
                BEGIN
                    IF NEW.action = 'RETURN_CREATE' AND NEW.entity = 'customer_returns' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit refused by the test';
                    END IF;
                END""");
        try {
            assertThatThrownBy(() -> returns.create(input(receipt, saleLine, "1"), UUID.randomUUID().toString()))
                    .hasMessageContaining("audit refused by the test");
        } finally {
            migration.jdbc().execute("DROP TRIGGER trg_test_audit_log_refuse_return");
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_returns WHERE document_id = ?", Integer.class,
                receipt)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_documents WHERE parent_id = ?", Integer.class,
                receipt)).isZero();
        assertThat(jdbc.queryForObject("SELECT returned_quantity_base FROM sales_document_lines WHERE id = ?",
                BigDecimal.class, saleLine)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE lot_id = ?", Long.class, lot))
                .isEqualTo(movementsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_refunds r JOIN sales_documents d"
                + " ON d.id = r.credit_note_id WHERE d.parent_id = ?", Integer.class, receipt)).isZero();
        assertThat(counter("CR")).isEqualTo(crBefore);
        assertThat(counter("CN")).isEqualTo(cnBefore);
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", BigDecimal.class, lot))
                .isEqualByComparingTo("8");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
    }

    /** A cheque receipt: its refunds need no cash session. */
    private long soldReceipt(String quantity) {
        SaleDetail draft = documents.create(new DraftInput("receipt", null, null, null, null));
        draft = lines.add(draft.id(), new LineInput(product, null, new BigDecimal(quantity), null, null, null, null,
                null));
        return confirmation.confirm(draft.id(), List.of(new Tender("cheque", draft.totals().ttc(), "CHQ-1")), null)
                .id();
    }

    private long saleLine(long document) {
        return jdbc.queryForObject("SELECT id FROM sales_document_lines WHERE document_id = ?", Long.class, document);
    }

    private static ReturnInput input(long document, long saleLine, String quantity) {
        BigDecimal ttc = UNIT_TTC.multiply(new BigDecimal(quantity));
        return new ReturnInput(document, "Erreur de produit", List.of(new ReturnLineInput(saleLine,
                new BigDecimal(quantity), null, true, "restock", null, null)),
                List.of(new Tender("cheque", ttc, "CHQ-R")));
    }

    private int counter(String key) {
        Integer last = jdbc.queryForObject("SELECT MAX(last_number) FROM document_sequences WHERE doc_key = ?",
                Integer.class, key);
        return last == null ? 0 : last;
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
