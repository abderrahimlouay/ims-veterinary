package com.imsvet.ims.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.finance.application.AccountPayments;
import com.imsvet.ims.finance.application.AccountPayments.AllocationInput;
import com.imsvet.ims.finance.application.AccountPayments.PaymentInput;
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
 * CT-11 (BR-PAY-002, BR-PAY-010, ADR-134): payments allocating to the same invoice at the same time serialise on the
 * invoice row, and applications of the same advance on the customer row, so what payments in force allocate never
 * exceeds the invoice's open amount nor any payment.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class AccountPaymentConcurrencyTest {

    private static final String[] PERMISSIONS = { "payment.customer.create", "payment.read" };

    @Autowired
    private AccountPayments payments;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @RepeatedTest(10)
    void paymentsRacingForOneInvoiceNeverAllocateMoreThanItsOpenAmount() throws Exception {
        long user = new TestUsers(jdbc, encoder).create("Accountant").id();
        long customer = customer();
        long invoice = invoice(customer, user, "100.00");
        List<Callable<ErrorCode>> racers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            racers.add(() -> {
                payments.record(new PaymentInput(customer, "cheque", new BigDecimal("30"), "CHQ",
                        List.of(new AllocationInput(invoice, new BigDecimal("30")))), null);
                return null;
            });
        }

        List<ErrorCode> outcomes = race(user, racers);

        assertThat(outcomes).filteredOn(o -> o == null).hasSize(3);
        assertThat(outcomes).filteredOn(o -> o != null).containsOnly(ErrorCode.PAYMENT_ALLOCATION_EXCEEDED);
        assertThat(allocatedTo(invoice)).isEqualByComparingTo("90.00");
    }

    @RepeatedTest(10)
    void applicationsOfOneAdvanceNeverExceedIt() throws Exception {
        long user = new TestUsers(jdbc, encoder).create("Accountant").id();
        long customer = customer();
        List<Long> invoices = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            invoices.add(invoice(customer, user, "100.00"));
        }
        TestActor.signIn(user, PERMISSIONS);
        long advance;
        try {
            advance = payments.record(new PaymentInput(customer, "cheque", new BigDecimal("50"), "CHQ", List.of()), null)
                    .id();
        } finally {
            TestActor.signOut();
        }
        List<Callable<ErrorCode>> racers = new ArrayList<>();
        for (long invoice : invoices) {
            racers.add(() -> {
                payments.apply(advance, List.of(new AllocationInput(invoice, new BigDecimal("20"))), null);
                return null;
            });
        }

        List<ErrorCode> outcomes = race(user, racers);

        assertThat(outcomes).filteredOn(o -> o == null).hasSize(2);
        assertThat(outcomes).filteredOn(o -> o != null).containsOnly(ErrorCode.PAYMENT_ALLOCATION_EXCEEDED);
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM payment_allocations WHERE payment_id = ?",
                BigDecimal.class, advance)).isEqualByComparingTo("40.00");
    }

    private BigDecimal allocatedTo(long invoice) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM payment_allocations WHERE invoice_id = ?",
                BigDecimal.class, invoice);
    }

    private long customer() {
        String name = "Ferme Course " + System.nanoTime();
        jdbc.update("INSERT INTO customers (name, created_at, updated_at) VALUES (?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                name);
        return jdbc.queryForObject("SELECT id FROM customers WHERE name = ?", Long.class, name);
    }

    private long invoice(long customer, long user, String ttc) {
        String number = "INV-CT11-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO sales_documents (doc_type, number, status, customer_id, is_credit_sale, due_date, issued_at,
                    total_ttc, created_by, created_at, updated_at)
                VALUES ('invoice', ?, 'confirmed', ?, TRUE, CURRENT_DATE, UTC_TIMESTAMP(6), ?, ?, UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6))""", number, customer, new BigDecimal(ttc), user);
        return jdbc.queryForObject("SELECT id FROM sales_documents WHERE number = ?", Long.class, number);
    }

    private List<ErrorCode> race(long user, List<Callable<ErrorCode>> actions) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        try {
            List<Future<ErrorCode>> futures = new ArrayList<>();
            for (Callable<ErrorCode> action : actions) {
                futures.add(pool.submit(() -> {
                    TestActor.signIn(user, PERMISSIONS);
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
