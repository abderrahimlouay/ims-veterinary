package com.imsvet.ims.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.MigrationAccount;
import com.imsvet.ims.support.TestLots;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * BR-PAY-003, SD-102, SD-139, SD-144: payments, allocations, refunds and supplier credit-note applications cannot be
 * changed or removed, even by the migration account; a supplier invoice's cancellation columns go together and only a
 * credit note names a supplier return.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class PaymentLedgerAppendOnlyTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MigrationAccount migration;

    private long payment;
    private long refund;
    private long creditApplication;
    private long supplier;
    private long user;

    @BeforeEach
    void committedPayment() {
        user = new TestLots(jdbc).fixtureUser();
        jdbc.update("INSERT INTO sales_documents (doc_type, created_by, created_at, updated_at)"
                + " VALUES ('receipt', ?, NOW(6), NOW(6))", user);
        long document = jdbc.queryForObject("SELECT MAX(id) FROM sales_documents", Long.class);
        jdbc.update("INSERT INTO customer_payments (method, amount, reference, paid_at, created_by)"
                + " VALUES ('cheque', 10, 'C-1', NOW(6), ?)", user);
        payment = jdbc.queryForObject("SELECT MAX(id) FROM customer_payments", Long.class);
        jdbc.update("INSERT INTO payment_allocations (payment_id, invoice_id, amount, created_at, created_by)"
                + " VALUES (?, ?, 10, NOW(6), ?)", payment, document, user);
        jdbc.update("INSERT INTO customer_refunds (credit_note_id, method, amount, reference, refunded_at, created_by)"
                + " VALUES (?, 'cheque', 10, 'C-2', NOW(6), ?)", document, user);
        refund = jdbc.queryForObject("SELECT MAX(id) FROM customer_refunds", Long.class);
        String name = "Append-only supplier " + System.nanoTime();
        jdbc.update("INSERT INTO suppliers (name, created_at, updated_at) VALUES (?, NOW(6), NOW(6))", name);
        supplier = jdbc.queryForObject("SELECT id FROM suppliers WHERE name = ?", Long.class, name);
        long invoice = supplierInvoice("FA-" + System.nanoTime(), false);
        long note = supplierInvoice("AV-" + System.nanoTime(), true);
        jdbc.update("""
                INSERT INTO supplier_credit_allocations (credit_note_id, invoice_id, amount, created_at, created_by)
                VALUES (?, ?, 5, NOW(6), ?)""", note, invoice, user);
        creditApplication = jdbc.queryForObject("SELECT MAX(id) FROM supplier_credit_allocations", Long.class);
    }

    @Test
    void supplierCreditApplicationsAreAppendOnly() {
        assertSignal("UPDATE supplier_credit_allocations SET amount = 999 WHERE id = " + creditApplication);
        assertSignal("DELETE FROM supplier_credit_allocations WHERE id = " + creditApplication);
    }

    @Test
    void aSupplierInvoiceIsCancelledWithItsReasonByAndAtAndOnlyACreditNoteSettlesAReturn() {
        long invoice = supplierInvoice("FA-C-" + System.nanoTime(), false);
        assertThatThrownBy(() -> jdbc.update("UPDATE supplier_invoices SET status = 'cancelled' WHERE id = ?", invoice))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_supplier_invoices_cancelled");
        jdbc.update("""
                UPDATE supplier_invoices SET status = 'cancelled', cancel_reason = 'x', cancelled_by = ?,
                    cancelled_at = NOW(6) WHERE id = ?""", user, invoice);
        jdbc.update("""
                INSERT INTO supplier_returns (number, supplier_id, reason, created_by, created_at)
                VALUES (?, ?, 'x', ?, NOW(6))""", "SR-AO-" + System.nanoTime(), supplier, user);
        long returned = jdbc.queryForObject("SELECT MAX(id) FROM supplier_returns", Long.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE supplier_invoices SET supplier_return_id = ? WHERE id = ?",
                returned, invoice)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_supplier_invoices_return");
    }

    private long supplierInvoice(String number, boolean creditNote) {
        jdbc.update("""
                INSERT INTO supplier_invoices (supplier_id, invoice_number, invoice_date, due_date, total_ttc,
                    is_credit_note, created_by, created_at)
                VALUES (?, ?, CURRENT_DATE, CURRENT_DATE, 10, ?, ?, NOW(6))""", supplier, number, creditNote, user);
        return jdbc.queryForObject("SELECT id FROM supplier_invoices WHERE supplier_id = ? AND invoice_number = ?",
                Long.class, supplier, number);
    }

    @Test
    void refundsAreAppendOnly() {
        assertSignal("UPDATE customer_refunds SET amount = 999 WHERE id = " + refund);
        assertSignal("DELETE FROM customer_refunds WHERE id = " + refund);
    }

    @Test
    void paymentsAreAppendOnly() {
        assertSignal("UPDATE customer_payments SET amount = 999 WHERE id = " + payment);
        assertSignal("DELETE FROM customer_payments WHERE id = " + payment);
    }

    @Test
    void allocationsAreAppendOnly() {
        assertSignal("UPDATE payment_allocations SET amount = 999 WHERE payment_id = " + payment);
        assertSignal("DELETE FROM payment_allocations WHERE payment_id = " + payment);
    }

    private void assertSignal(String sql) {
        assertThatThrownBy(() -> migration.jdbc().update(sql)).satisfies(thrown ->
                assertThat(((DataAccessException) thrown).getMostSpecificCause())
                        .isInstanceOfSatisfying(SQLException.class, e -> {
                            assertThat(e.getSQLState()).isEqualTo("45000");
                            assertThat(e.getMessage()).contains("append-only");
                        }));
    }
}
