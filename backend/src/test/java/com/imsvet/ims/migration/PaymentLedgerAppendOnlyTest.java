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

/** BR-PAY-003, SD-102, SD-139: payments, allocations and refunds cannot be changed or removed, even by the migration account. */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class PaymentLedgerAppendOnlyTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MigrationAccount migration;

    private long payment;
    private long refund;

    @BeforeEach
    void committedPayment() {
        long user = new TestLots(jdbc).fixtureUser();
        jdbc.update("INSERT INTO sales_documents (doc_type, created_by, created_at, updated_at)"
                + " VALUES ('receipt', ?, NOW(6), NOW(6))", user);
        long document = jdbc.queryForObject("SELECT MAX(id) FROM sales_documents", Long.class);
        jdbc.update("INSERT INTO customer_payments (method, amount, reference, paid_at, created_by)"
                + " VALUES ('cheque', 10, 'C-1', NOW(6), ?)", user);
        payment = jdbc.queryForObject("SELECT MAX(id) FROM customer_payments", Long.class);
        jdbc.update("INSERT INTO payment_allocations (payment_id, invoice_id, amount) VALUES (?, ?, 10)", payment,
                document);
        jdbc.update("INSERT INTO customer_refunds (credit_note_id, method, amount, reference, refunded_at, created_by)"
                + " VALUES (?, 'cheque', 10, 'C-2', NOW(6), ?)", document, user);
        refund = jdbc.queryForObject("SELECT MAX(id) FROM customer_refunds", Long.class);
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
