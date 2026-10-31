package com.imsvet.ims.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.finance.application.CashSessions;
import com.imsvet.ims.sales.application.PaymentRecorder;
import com.imsvet.ims.sales.application.PaymentRecorder.RecordedPayments;
import com.imsvet.ims.sales.application.PaymentRecorder.Tender;
import com.imsvet.ims.support.TestActor;
import com.imsvet.ims.support.TestUsers;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payments of a sale (ADR-118): valid per BR-PAY-001, at most the total (BR-PAY-002), a cash part needs the open
 * session and posts the amount (BR-PAY-006), anonymous allowed; reversal by a new row, once, with the cash going out
 * of the same session, audited (BR-PAY-003), refused on a closed session (BR-CASH-004).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CounterPaymentsTest {

    private static final BigDecimal TTC = new BigDecimal("45.00");

    @Autowired
    private PaymentRecorder recorder;

    @Autowired
    private CashSessions sessions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    private long cashier;
    private long document;

    @BeforeEach
    void setUp() {
        cashier = new TestUsers(jdbc, encoder).create("Seller").id();
        TestActor.signIn(cashier, "cash.open");
        jdbc.update("INSERT INTO sales_documents (doc_type, number, status, total_ttc, created_by, created_at, updated_at)"
                + " VALUES ('receipt', 'RCT-T-" + System.nanoTime() % 100000000 + "', 'confirmed', 45, ?, NOW(6), NOW(6))",
                cashier);
        document = jdbc.queryForObject("SELECT MAX(id) FROM sales_documents", Long.class);
    }

    @AfterEach
    void signOut() {
        TestActor.signOut();
    }

    @Test
    void anAnonymousCashPaymentPostsWhatIsKeptToTheOpenSession() {
        long session = sessions.open(new BigDecimal("100")).id();
        RecordedPayments recorded = recorder.record(document, null, TTC, List.of(cash("45.00")));
        assertThat(recorded.cashSessionId()).isEqualTo(session);
        assertThat(recorded.paymentIds()).hasSize(1);
        long payment = recorded.paymentIds().get(0);
        assertThat(jdbc.queryForObject("SELECT customer_id FROM customer_payments WHERE id = ?", Long.class, payment))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT amount FROM payment_allocations WHERE payment_id = ? AND invoice_id = ?",
                BigDecimal.class, payment, document)).isEqualByComparingTo("45.00");
        assertThat(jdbc.queryForObject("SELECT amount FROM cash_movements WHERE customer_payment_id = ?",
                BigDecimal.class, payment)).isEqualByComparingTo("45.00");
        assertThat(sessions.current().orElseThrow().expectedAmount()).isEqualByComparingTo("145.00");
    }

    @Test
    void aSplitPaymentPostsOnlyItsCashPart() {
        sessions.open(BigDecimal.ZERO);
        RecordedPayments recorded = recorder.record(document, null, TTC,
                List.of(cash("15"), new Tender("cheque", new BigDecimal("30"), " CHQ-1 ")));
        assertThat(recorded.paymentIds()).hasSize(2);
        assertThat(sessions.current().orElseThrow().expectedAmount()).isEqualByComparingTo("15.00");
        assertThat(jdbc.queryForObject("SELECT reference FROM customer_payments WHERE id = ?", String.class,
                recorded.paymentIds().get(1))).isEqualTo("CHQ-1");
        assertThat(jdbc.queryForObject("SELECT cash_session_id FROM customer_payments WHERE id = ?", Long.class,
                recorded.paymentIds().get(1))).isNull();
    }

    @Test
    void chequesAndTransfersNeedNoSession() {
        RecordedPayments recorded = recorder.record(document, null, TTC,
                List.of(new Tender("bank_transfer", TTC, "VIR-9")));
        assertThat(recorded.cashSessionId()).isNull();
    }

    @Test
    void aCashPartWithoutAnOpenSessionWritesNothing() {
        long before = count("customer_payments");
        assertThatThrownBy(() -> recorder.record(document, null, TTC, List.of(cash("45"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CASH_SESSION_REQUIRED));
        assertThat(count("customer_payments")).isEqualTo(before);
    }

    @Test
    void invalidTendersAreRefused() {
        assertInvalid(new Tender("bitcoin", TTC, null), "UNKNOWN");
        assertInvalid(new Tender(null, TTC, null), "REQUIRED");
        assertInvalid(new Tender("cash", null, null), "REQUIRED");
        assertInvalid(new Tender("cash", BigDecimal.ZERO, null), "POSITIVE");
        assertInvalid(new Tender("cash", new BigDecimal("1.234"), null), "SCALE");
        assertInvalid(new Tender("cheque", TTC, " "), "REQUIRED");
        assertInvalid(new Tender("cheque", TTC, "x".repeat(81)), "SIZE");
    }

    @Test
    void paymentsCannotExceedTheTotal() {
        sessions.open(BigDecimal.ZERO);
        assertThatThrownBy(() -> recorder.record(document, null, TTC, List.of(cash("50.00"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYMENT_ALLOCATION_EXCEEDED));
    }

    @Test
    void aReversalIsANewRowOnceWithTheCashGoingOutAndAudited() {
        sessions.open(new BigDecimal("10"));
        long payment = recorder.record(document, null, TTC, List.of(cash("45"))).paymentIds().get(0);
        List<Long> reversals = recorder.reverseAll(document, "erreur de saisie");
        assertThat(reversals).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT reverses_payment_id FROM customer_payments WHERE id = ?", Long.class,
                reversals.get(0))).isEqualTo(payment);
        assertThat(jdbc.queryForObject("SELECT amount FROM customer_payments WHERE id = ?", BigDecimal.class, payment))
                .isEqualByComparingTo("45.00");
        assertThat(sessions.current().orElseThrow().expectedAmount()).isEqualByComparingTo("10.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'PAYMENT_REVERSE' AND entity_id = ?",
                Long.class, payment)).isOne();
        assertThat(recorder.reverseAll(document, "encore")).isEmpty();
    }

    @Test
    void aReversalOnAClosedSessionIsRefused() {
        long session = sessions.open(BigDecimal.ZERO).id();
        recorder.record(document, null, TTC, List.of(cash("45")));
        sessions.close(session, new BigDecimal("45"));
        assertThatThrownBy(() -> recorder.reverseAll(document, "tard"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CASH_SESSION_CLOSED));
    }

    @Test
    void theDatabaseEnforcesTheRulesToo() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO customer_payments (method, amount, paid_at, created_by)"
                + " VALUES ('cheque', 5, NOW(6), ?)", cashier)).hasMessageContaining("ck_customer_payments_reference");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO customer_payments (method, amount, paid_at, created_by)"
                + " VALUES ('cash', 5, NOW(6), ?)", cashier)).hasMessageContaining("ck_customer_payments_cash_session");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO customer_payments (method, amount, reference, paid_at,"
                + " created_by) VALUES ('cheque', 0, 'C', NOW(6), ?)", cashier))
                .hasMessageContaining("ck_customer_payments_amount");
    }

    private void assertInvalid(Tender tender, String code) {
        assertThatThrownBy(() -> recorder.record(document, null, TTC, List.of(tender)))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.violations().get(0).code()).isEqualTo(code));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static Tender cash(String amount) {
        return new Tender("cash", new BigDecimal(amount), null);
    }
}
