package com.imsvet.ims.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.finance.application.CashSessions;
import com.imsvet.ims.sales.application.PaymentRecorder.Tender;
import com.imsvet.ims.sales.application.RefundRecorder;
import com.imsvet.ims.sales.application.RefundRecorder.RecordedRefund;
import com.imsvet.ims.sales.application.RefundRecorder.RefundRequest;
import com.imsvet.ims.support.TestActor;
import com.imsvet.ims.support.TestUsers;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
 * Refunds of a credit note (ADR-128, BR-RET-006, BR-RET-011): the tenders equal its TTC exactly
 * ({@code REFUND_TOTAL_MISMATCH}), each valid per BR-PAY-001, anonymous allowed; a cash part needs the refunder's own
 * open session, goes out of it and is refused before anything is written when the session's expected cash would fall
 * below zero ({@code CASH_INSUFFICIENT}); each refund is audited {@code REFUND_CREATE}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CounterRefundsTest {

    private static final BigDecimal TTC = new BigDecimal("45.00");

    @Autowired
    private RefundRecorder refunds;

    @Autowired
    private CashSessions sessions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    private long refunder;
    private long receipt;
    private long creditNote;

    @BeforeEach
    void setUp() {
        refunder = new TestUsers(jdbc, encoder).create("Pharmacist").id();
        TestActor.signIn(refunder, "cash.open");
        long suffix = System.nanoTime() % 100000000;
        receipt = document("receipt", "RCT-R-" + suffix, null);
        creditNote = document("credit_note", "CN-R-" + suffix, receipt);
    }

    @AfterEach
    void signOut() {
        TestActor.signOut();
    }

    @Test
    void anAnonymousCashRefundGoesOutOfTheRefundersSessionAndIsAudited() {
        long session = sessions.open(new BigDecimal("100")).id();

        List<RecordedRefund> recorded = refunds.refund(request(TTC, List.of(cash("45.00"))));

        assertThat(recorded).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("cash");
            assertThat(r.amount()).isEqualByComparingTo("45.00");
            assertThat(r.cashSessionId()).isEqualTo(session);
        });
        long refund = recorded.get(0).id();
        assertThat(jdbc.queryForObject("SELECT customer_id FROM customer_refunds WHERE id = ?", Long.class, refund))
                .isNull();
        Map<String, Object> movement = jdbc.queryForMap("""
                SELECT direction, method, amount, cash_session_id, customer_payment_id FROM cash_movements
                WHERE customer_refund_id = ?""", refund);
        assertThat(movement.get("direction")).isEqualTo("out");
        assertThat(movement.get("method")).isEqualTo("cash");
        assertThat((BigDecimal) movement.get("amount")).isEqualByComparingTo("45.00");
        assertThat(((Number) movement.get("cash_session_id")).longValue()).isEqualTo(session);
        assertThat(movement.get("customer_payment_id")).isNull();
        assertThat(sessions.current().orElseThrow().expectedAmount()).isEqualByComparingTo("55.00");
        String audit = jdbc.queryForObject("""
                SELECT new_values FROM audit_log WHERE action = 'REFUND_CREATE' AND entity = 'customer_refunds'
                  AND entity_id = ?""", String.class, refund);
        assertThat(audit).contains("\"creditNoteId\": " + creditNote).contains("\"documentId\": " + receipt)
                .contains("\"method\": \"cash\"").contains("\"amount\": \"45.00\"").contains("\"cash\"")
                .contains("\"cashSessionId\": " + session);
        assertThat(refunds.refundsOf(creditNote)).extracting(RecordedRefund::id).containsExactly(refund);
    }

    @Test
    void aSplitRefundPostsOnlyItsCashPartAndMayUseAnotherMethodThanTheSale() {
        sessions.open(new BigDecimal("20"));

        List<RecordedRefund> recorded = refunds.refund(request(TTC,
                List.of(cash("15"), new Tender("bank_transfer", new BigDecimal("30"), " TRF-9 "))));

        assertThat(recorded).extracting(RecordedRefund::method).containsExactly("cash", "bank_transfer");
        assertThat(recorded.get(1).reference()).isEqualTo("TRF-9");
        assertThat(recorded.get(1).cashSessionId()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_movements WHERE customer_refund_id IN (?, ?)",
                Integer.class, recorded.get(0).id(), recorded.get(1).id())).isEqualTo(1);
        assertThat(sessions.current().orElseThrow().expectedAmount()).isEqualByComparingTo("5.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'REFUND_CREATE'"
                + " AND entity_id IN (?, ?)", Integer.class, recorded.get(0).id(), recorded.get(1).id())).isEqualTo(2);
    }

    @Test
    void theTendersMustEqualTheCreditNoteTotalExactly() {
        assertMismatch(List.of(new Tender("cheque", new BigDecimal("44.99"), "C-1")), "44.99");
        assertMismatch(List.of(new Tender("cheque", new BigDecimal("45.01"), "C-1")), "45.01");
        assertMismatch(List.of(), "0.00");
        assertThat(count()).isZero();
    }

    @Test
    void aCreditNoteOfZeroNeedsNoTender() {
        assertThat(refunds.refund(request(BigDecimal.ZERO, List.of()))).isEmpty();
        assertThat(refunds.refund(request(BigDecimal.ZERO, null))).isEmpty();
    }

    @Test
    void everyTenderIsValidated() {
        assertInvalid(new Tender("card", TTC, null), "UNKNOWN");
        assertInvalid(new Tender(null, TTC, null), "REQUIRED");
        assertInvalid(new Tender("cheque", TTC, " "), "REQUIRED");
        assertInvalid(new Tender("cash", new BigDecimal("45.001"), null), "SCALE");
        assertInvalid(new Tender("cash", new BigDecimal("-45"), null), "POSITIVE");
        assertInvalid(new Tender("cheque", TTC, "C".repeat(81)), "SIZE");
        assertThat(count()).isZero();
    }

    @Test
    void cashNeedsTheRefundersOwnOpenSession() {
        long other = new TestUsers(jdbc, encoder).create("Pharmacist").id();
        TestActor.signIn(other, "cash.open");
        sessions.open(new BigDecimal("500"));
        TestActor.signIn(refunder, "cash.open");

        assertThatThrownBy(() -> refunds.refund(request(TTC, List.of(cash("45")))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CASH_SESSION_REQUIRED));
        assertThat(count()).isZero();
    }

    @Test
    void cashIsRefusedBeforeAnythingIsWrittenWhenTheSessionWouldGoBelowZero() {
        long session = sessions.open(new BigDecimal("40")).id();

        assertThatThrownBy(() -> refunds.refund(request(TTC,
                List.of(new Tender("cheque", new BigDecimal("4.99"), "C-2"), cash("40.01")))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.CASH_INSUFFICIENT);
                    assertThat(e.context()).containsEntry("cashSessionId", session).containsEntry("expected", "40.00")
                            .containsEntry("requested", "40.01");
                });
        assertThat(count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_movements WHERE cash_session_id = ?", Integer.class,
                session)).isZero();

        refunds.refund(request(new BigDecimal("40.00"), List.of(cash("40"))));
        assertThat(sessions.current().orElseThrow().expectedAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void theDatabaseRefusesAnInvalidRefundRow() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO customer_refunds (credit_note_id, method, amount, refunded_at, created_by)
                VALUES (?, 'cash', 5, NOW(6), ?)""", creditNote, refunder))
                .hasMessageContaining("ck_customer_refunds_cash_session");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO customer_refunds (credit_note_id, method, amount, refunded_at, created_by)
                VALUES (?, 'cheque', 5, NOW(6), ?)""", creditNote, refunder))
                .hasMessageContaining("ck_customer_refunds_reference");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO customer_refunds (credit_note_id, method, amount, reference, refunded_at, created_by)
                VALUES (?, 'card', 5, 'X', NOW(6), ?)""", creditNote, refunder))
                .hasMessageContaining("ck_customer_refunds_method");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO customer_refunds (credit_note_id, method, amount, reference, refunded_at, created_by)
                VALUES (?, 'cheque', 0, 'X', NOW(6), ?)""", creditNote, refunder))
                .hasMessageContaining("ck_customer_refunds_amount");
    }

    private RefundRequest request(BigDecimal ttc, List<Tender> tenders) {
        return new RefundRequest(creditNote, receipt, null, ttc, tenders, List.of("cash"));
    }

    private void assertMismatch(List<Tender> tenders, String given) {
        assertThatThrownBy(() -> refunds.refund(request(TTC, tenders)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.REFUND_TOTAL_MISMATCH);
                    assertThat(e.context()).containsEntry("expected", "45.00").containsEntry("given", given);
                });
    }

    private void assertInvalid(Tender tender, String code) {
        assertThatThrownBy(() -> refunds.refund(request(TTC, List.of(tender))))
                .isInstanceOfSatisfying(ValidationException.class, e -> {
                    assertThat(e.violations().get(0).code()).isEqualTo(code);
                    assertThat(e.violations().get(0).field()).startsWith("refund[0].");
                });
    }

    private long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM customer_refunds WHERE credit_note_id = ?", Long.class,
                creditNote);
    }

    private long document(String type, String number, Long parent) {
        jdbc.update("""
                INSERT INTO sales_documents (doc_type, number, status, parent_id, total_ttc, created_by, created_at,
                                             updated_at)
                VALUES (?, ?, 'confirmed', ?, 45, ?, NOW(6), NOW(6))""", type, number, parent, refunder);
        return jdbc.queryForObject("SELECT id FROM sales_documents WHERE doc_type = ? AND number = ?", Long.class,
                type, number);
    }

    private static Tender cash(String amount) {
        return new Tender("cash", new BigDecimal(amount), null);
    }
}
