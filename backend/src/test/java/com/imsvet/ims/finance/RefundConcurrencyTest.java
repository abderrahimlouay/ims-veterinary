package com.imsvet.ims.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.finance.application.CashSessions;
import com.imsvet.ims.sales.application.PaymentRecorder.Tender;
import com.imsvet.ims.sales.application.RefundRecorder;
import com.imsvet.ims.sales.application.RefundRecorder.RefundRequest;
import com.imsvet.ims.support.TestActor;
import com.imsvet.ims.support.TestUsers;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BR-RET-011, BR-CASH-004 under concurrency (C): two cash refunds that together exceed the session's cash serialise on
 * the session row, so exactly one is paid and the expected cash never falls below zero; a refund racing the closing of
 * its session is either counted in the closing or refused, never posted to a closed session.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class RefundConcurrencyTest {

    @Autowired
    private RefundRecorder refunds;

    @Autowired
    private CashSessions sessions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @RepeatedTest(10)
    void twoRefundsExceedingTheCashTogetherLeaveExactlyOnePaid() throws Exception {
        long cashier = new TestUsers(jdbc, encoder).create("Pharmacist").id();
        long session = open(cashier, "40");
        long first = creditNote(cashier);
        long second = creditNote(cashier);

        List<ErrorCode> outcomes = race(cashier, () -> refund(first), () -> refund(second));

        assertThat(outcomes).filteredOn(o -> o == null).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o != null).containsExactly(ErrorCode.CASH_INSUFFICIENT);
        assertThat(jdbc.queryForObject("""
                SELECT s.opening_amount + COALESCE(SUM(CASE m.direction WHEN 'in' THEN m.amount ELSE -m.amount END), 0)
                FROM cash_sessions s LEFT JOIN cash_movements m ON m.cash_session_id = s.id AND m.method = 'cash'
                WHERE s.id = ? GROUP BY s.id, s.opening_amount""", BigDecimal.class, session))
                .isEqualByComparingTo("10.00");
    }

    @RepeatedTest(10)
    void aRefundRacingTheClosingIsCountedOrRefusedNeverPostedAfterIt() throws Exception {
        long cashier = new TestUsers(jdbc, encoder).create("Pharmacist").id();
        long session = open(cashier, "40");
        long note = creditNote(cashier);

        List<ErrorCode> outcomes = race(cashier, () -> refund(note), () -> {
            sessions.close(session, new BigDecimal("10"));
            return null;
        });

        Integer refunded = jdbc.queryForObject("SELECT COUNT(*) FROM customer_refunds WHERE credit_note_id = ?",
                Integer.class, note);
        BigDecimal expected = jdbc.queryForObject("SELECT expected_amount FROM cash_sessions WHERE id = ?",
                BigDecimal.class, session);
        if (refunded == 1) {
            assertThat(outcomes).containsOnlyNulls();
            assertThat(expected).isEqualByComparingTo("10.00");
        } else {
            assertThat(outcomes).containsOnly(null, ErrorCode.CASH_SESSION_REQUIRED);
            assertThat(expected).isEqualByComparingTo("40.00");
        }
    }

    private long open(long cashier, String amount) {
        TestActor.signIn(cashier, "cash.open");
        try {
            return sessions.open(new BigDecimal(amount)).id();
        } finally {
            TestActor.signOut();
        }
    }

    private long creditNote(long user) {
        String number = "CN-RC-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO sales_documents (doc_type, number, status, total_ttc, created_by, created_at, updated_at)
                VALUES ('credit_note', ?, 'confirmed', 30, ?, NOW(6), NOW(6))""", number, user);
        return jdbc.queryForObject("SELECT id FROM sales_documents WHERE number = ?", Long.class, number);
    }

    private ErrorCode refund(long creditNote) {
        refunds.refund(new RefundRequest(creditNote, creditNote, null, new BigDecimal("30.00"),
                List.of(new Tender("cash", new BigDecimal("30.00"), null)), List.of("cash")));
        return null;
    }

    @SafeVarargs
    private List<ErrorCode> race(long cashier, Callable<ErrorCode>... actions) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(actions.length);
        try {
            List<Future<ErrorCode>> futures = new ArrayList<>();
            for (Callable<ErrorCode> action : actions) {
                futures.add(pool.submit(() -> {
                    TestActor.signIn(cashier, "cash.open");
                    try {
                        start.await();
                        return action.call();
                    } catch (BusinessException e) {
                        return e.code();
                    } finally {
                        TestActor.signOut();
                    }
                }));
            }
            start.countDown();
            List<ErrorCode> outcomes = new ArrayList<>();
            for (Future<ErrorCode> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
