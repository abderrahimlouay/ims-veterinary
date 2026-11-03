package com.imsvet.ims.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.finance.application.SupplierInvoices;
import com.imsvet.ims.finance.application.SupplierInvoices.ApplicationInput;
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
 * ADR-133 item 3 under concurrency (CT-11 for credit notes): applications of one supplier credit note racing each other
 * serialise on the supplier row, so they never apply more than the credit note; an application racing the cancellation
 * of its invoice is either refused or makes the cancellation refused, never both done.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class SupplierCreditConcurrencyTest {

    @Autowired
    private SupplierInvoices invoices;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @RepeatedTest(10)
    void applicationsOfOneCreditNoteNeverExceedIt() throws Exception {
        long user = new TestUsers(jdbc, encoder).create("Accountant").id();
        long supplier = supplier();
        long note = document(supplier, user, true, "50");
        List<Long> targets = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            targets.add(document(supplier, user, false, "100"));
        }

        List<ErrorCode> outcomes = race(user, targets.stream().map(invoice -> (Action) () -> invoices.apply(note,
                List.of(new ApplicationInput(invoice, new BigDecimal("20"))), null)).toList());

        assertThat(outcomes).filteredOn(o -> o == null).hasSize(2);
        assertThat(outcomes).filteredOn(o -> o != null).containsOnly(ErrorCode.PAYMENT_ALLOCATION_EXCEEDED);
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM supplier_credit_allocations WHERE credit_note_id = ?",
                BigDecimal.class, note)).isEqualByComparingTo("40");
    }

    @RepeatedTest(10)
    void anApplicationRacingTheCancellationOfItsInvoiceNeverLeavesACancelledInvoiceSettled() throws Exception {
        long user = new TestUsers(jdbc, encoder).create("Accountant").id();
        long supplier = supplier();
        long note = document(supplier, user, true, "50");
        long invoice = document(supplier, user, false, "100");

        List<ErrorCode> outcomes = race(user, List.of(
                () -> invoices.apply(note, List.of(new ApplicationInput(invoice, new BigDecimal("20"))), null),
                () -> invoices.cancel(invoice, "Erreur")));

        String status = jdbc.queryForObject("SELECT status FROM supplier_invoices WHERE id = ?", String.class, invoice);
        Integer applied = jdbc.queryForObject("SELECT COUNT(*) FROM supplier_credit_allocations WHERE invoice_id = ?",
                Integer.class, invoice);
        if ("cancelled".equals(status)) {
            assertThat(applied).isZero();
            assertThat(outcomes.get(0)).isEqualTo(ErrorCode.VALIDATION_FAILED);
        } else {
            assertThat(applied).isEqualTo(1);
            assertThat(outcomes.get(1)).isEqualTo(ErrorCode.INVOICE_HAS_SETTLEMENTS);
        }
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private long supplier() {
        String name = "Labo course " + System.nanoTime();
        jdbc.update("INSERT INTO suppliers (name, created_at, updated_at) VALUES (?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                name);
        return jdbc.queryForObject("SELECT id FROM suppliers WHERE name = ?", Long.class, name);
    }

    private long document(long supplier, long user, boolean creditNote, String total) {
        String number = (creditNote ? "AV-" : "FA-") + System.nanoTime();
        jdbc.update("""
                INSERT INTO supplier_invoices (supplier_id, invoice_number, invoice_date, due_date, total_ttc,
                    is_credit_note, created_by, created_at)
                VALUES (?, ?, CURRENT_DATE, CURRENT_DATE, ?, ?, ?, UTC_TIMESTAMP(6))""", supplier, number,
                new BigDecimal(total), creditNote, user);
        return jdbc.queryForObject("SELECT id FROM supplier_invoices WHERE invoice_number = ?", Long.class, number);
    }

    private List<ErrorCode> race(long user, List<Action> actions) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        try {
            List<Future<ErrorCode>> futures = new ArrayList<>();
            for (Action action : actions) {
                futures.add(pool.submit(() -> {
                    TestActor.signIn(user, "supplierinvoice.manage");
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
