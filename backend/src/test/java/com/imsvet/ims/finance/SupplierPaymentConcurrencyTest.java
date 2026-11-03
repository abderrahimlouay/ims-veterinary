package com.imsvet.ims.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.finance.application.SupplierPayments;
import com.imsvet.ims.finance.application.SupplierPayments.AllocationInput;
import com.imsvet.ims.finance.application.SupplierPayments.PaymentInput;
import com.imsvet.ims.support.TestActor;
import com.imsvet.ims.support.TestUsers;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
 * CT-11 and CT-12 on the supplier side (ADR-132, ADR-135): payments racing for one invoice serialise on the supplier
 * row, so they never allocate more than its open amount; a payment reversed twice at once is reversed once; and an
 * application racing the reversal never leaves an allocation of a reversed payment counting.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class SupplierPaymentConcurrencyTest {

    private static final String[] PERMISSIONS = { "payment.supplier.create", "payment.read", "payment.reverse" };

    @Autowired
    private SupplierPayments payments;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @RepeatedTest(10)
    void paymentsRacingForOneInvoiceNeverAllocateMoreThanItsOpenAmount() throws Exception {
        long user = new TestUsers(jdbc, encoder).create("Accountant").id();
        long supplier = supplier();
        long invoice = invoice(supplier, user, "100");
        List<Action> racers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            racers.add(() -> payments.record(new PaymentInput(supplier, "cheque", new BigDecimal("30"), "CHQ",
                    List.of(new AllocationInput(invoice, new BigDecimal("30")))), null));
        }

        List<ErrorCode> outcomes = race(user, racers);

        assertThat(outcomes).filteredOn(o -> o == null).hasSize(3);
        assertThat(outcomes).filteredOn(o -> o != null).containsOnly(ErrorCode.PAYMENT_ALLOCATION_EXCEEDED);
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM supplier_payment_allocations WHERE invoice_id = ?",
                BigDecimal.class, invoice)).isEqualByComparingTo("90");
    }

    @RepeatedTest(10)
    void aPaymentReversedTwiceAtOnceIsReversedOnce() throws Exception {
        long user = new TestUsers(jdbc, encoder).create("Accountant").id();
        long supplier = supplier();
        long payment = advance(user, supplier);

        List<ErrorCode> outcomes = race(user, List.of(() -> payments.reverse(payment, "Erreur", null),
                () -> payments.reverse(payment, "Erreur", null)));

        assertThat(outcomes).containsExactlyInAnyOrder(null, ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM supplier_payments WHERE reverses_payment_id = ?",
                Integer.class, payment)).isEqualTo(1);
    }

    @RepeatedTest(10)
    void anApplicationRacingTheReversalNeverLeavesTheInvoiceSettledByAReversedPayment() throws Exception {
        long user = new TestUsers(jdbc, encoder).create("Accountant").id();
        long supplier = supplier();
        long invoice = invoice(supplier, user, "100");
        long payment = advance(user, supplier);

        List<ErrorCode> outcomes = race(user, List.of(
                () -> payments.apply(payment, List.of(new AllocationInput(invoice, new BigDecimal("30"))), null),
                () -> payments.reverse(payment, "Erreur", null)));

        assertThat(outcomes.get(1)).isNull();
        assertThat(outcomes.get(0)).isIn(null, ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(a.amount), 0) FROM supplier_payment_allocations a
                WHERE a.invoice_id = ? AND NOT EXISTS (SELECT 1 FROM supplier_payments r
                                                     WHERE r.reverses_payment_id = a.payment_id)""",
                BigDecimal.class, invoice)).isEqualByComparingTo("0");
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private long advance(long user, long supplier) {
        TestActor.signIn(user, PERMISSIONS);
        try {
            return payments.record(new PaymentInput(supplier, "cheque", new BigDecimal("50"), "CHQ", List.of()), null)
                    .id();
        } finally {
            TestActor.signOut();
        }
    }

    private long supplier() {
        String name = "Labo paiement course " + System.nanoTime();
        jdbc.update("INSERT INTO suppliers (name, created_at, updated_at) VALUES (?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                name);
        return jdbc.queryForObject("SELECT id FROM suppliers WHERE name = ?", Long.class, name);
    }

    private long invoice(long supplier, long user, String total) {
        String number = "FA-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO supplier_invoices (supplier_id, invoice_number, invoice_date, due_date, total_ttc,
                    created_by, created_at)
                VALUES (?, ?, CURRENT_DATE, CURRENT_DATE, ?, ?, UTC_TIMESTAMP(6))""", supplier, number,
                new BigDecimal(total), user);
        return jdbc.queryForObject("SELECT id FROM supplier_invoices WHERE invoice_number = ?", Long.class, number);
    }

    private List<ErrorCode> race(long user, List<Action> actions) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        try {
            List<Future<ErrorCode>> futures = new ArrayList<>();
            for (Action action : actions) {
                futures.add(pool.submit(() -> {
                    TestActor.signIn(user, PERMISSIONS);
                    try {
                        start.await();
                        action.run();
                        return null;
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
