package com.imsvet.ims.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.MySqlContainerConfiguration;
import com.imsvet.ims.TestcontainersConfiguration;
import java.math.BigDecimal;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Migration test (M) on a seeded database (database-migration.md §9): a separate schema is migrated to the end of
 * Phase 8 (V08_008) and filled with Phase 8 data (a credit invoice, an account payment allocated to it and a counter
 * receipt paid at once), then upgraded with every Phase 9 migration. Nothing fails; every allocation keeps its payment,
 * invoice and amount with a new id, dated and attributed as its payment (SD-145); the balance of the customer is the
 * same; the same payment may now be applied to the same invoice again; allocations stay append-only; the supplier
 * invoice tables start empty. Fictional data only (ADR-114).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class Phase9UpgradeMigrationTest {

    private static final String SCHEMA = "ims_upgrade_check_9";
    private static final String LAST = "09.006";

    @Autowired
    private MySQLContainer mysql;

    private JdbcTemplate root;
    private DriverManagerDataSource upgraded;

    @BeforeEach
    void emptySchema() {
        root = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), "root",
                MySqlContainerConfiguration.MYSQL_PASSWORD));
        root.execute("DROP DATABASE IF EXISTS " + SCHEMA);
        root.execute("CREATE DATABASE " + SCHEMA);
        upgraded = new DriverManagerDataSource("jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306)
                + "/" + SCHEMA + "?connectionTimeZone=UTC", "root", MySqlContainerConfiguration.MYSQL_PASSWORD);
    }

    @AfterEach
    void dropSchema() {
        root.execute("DROP DATABASE IF EXISTS " + SCHEMA);
    }

    @Test
    void phase9MigrationsUpgradeADatabaseHoldingPhase8Data() {
        flyway("08.008").migrate();
        JdbcTemplate db = new JdbcTemplate(upgraded);
        db.update("""
                INSERT INTO users (username, password_hash, full_name, role_id, is_active, must_change_password, created_at)
                SELECT 'upgrade-user', 'not-a-login', 'Upgrade user', id, TRUE, FALSE, UTC_TIMESTAMP(6)
                FROM roles WHERE name = 'Accountant'""");
        long user = db.queryForObject("SELECT id FROM users WHERE username = 'upgrade-user'", Long.class);
        db.update("""
                INSERT INTO customers (name, created_at, updated_at)
                VALUES ('Ferme Mise à niveau', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""");
        long customer = db.queryForObject("SELECT id FROM customers WHERE name = 'Ferme Mise à niveau'", Long.class);
        String document = """
                INSERT INTO sales_documents (doc_type, number, status, customer_id, is_credit_sale, due_date, issued_at,
                                             total_ttc, created_by, created_at, updated_at)
                VALUES (?, ?, 'confirmed', ?, ?, '2026-12-01', UTC_TIMESTAMP(6), ?, ?, UTC_TIMESTAMP(6),
                        UTC_TIMESTAMP(6))""";
        db.update(document, "invoice", "INV-2026-000001", customer, true, new BigDecimal("100.00"), user);
        long invoice = db.queryForObject("SELECT id FROM sales_documents WHERE number = 'INV-2026-000001'", Long.class);
        db.update(document, "receipt", "RCT-2026-000001", customer, false, new BigDecimal("12.00"), user);
        long receipt = db.queryForObject("SELECT id FROM sales_documents WHERE number = 'RCT-2026-000001'", Long.class);
        String payment = """
                INSERT INTO customer_payments (customer_id, method, amount, reference, paid_at, created_by)
                VALUES (?, 'cheque', ?, ?, '2026-10-30 09:15:00', ?)""";
        db.update(payment, customer, new BigDecimal("60.00"), "CHQ-ACCOUNT", user);
        long account = db.queryForObject("SELECT id FROM customer_payments WHERE reference = 'CHQ-ACCOUNT'", Long.class);
        db.update(payment, customer, new BigDecimal("12.00"), "CHQ-COUNTER", user);
        long counter = db.queryForObject("SELECT id FROM customer_payments WHERE reference = 'CHQ-COUNTER'", Long.class);
        db.update("INSERT INTO payment_allocations (payment_id, invoice_id, amount) VALUES (?, ?, 40.00), (?, ?, 12.00)",
                account, invoice, counter, receipt);
        String balance = """
                SELECT (SELECT SUM(total_ttc) FROM sales_documents WHERE customer_id = ? AND is_credit_sale)
                     - (SELECT SUM(p.amount) FROM customer_payments p WHERE p.customer_id = ?
                          AND NOT EXISTS (SELECT 1 FROM payment_allocations a JOIN sales_documents d ON d.id = a.invoice_id
                                          WHERE a.payment_id = p.id AND NOT d.is_credit_sale))""";
        BigDecimal before = db.queryForObject(balance, BigDecimal.class, customer, customer);

        flyway(LAST).migrate();

        MigrationInfo[] applied = flyway(LAST).info().applied();
        assertThat(applied).allSatisfy(m -> assertThat(m.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(applied[applied.length - 1].getVersion().getVersion()).isEqualTo(LAST);
        assertThat(db.queryForList("""
                SELECT CONCAT_WS(' ', payment_id, invoice_id, CAST(amount AS CHAR), created_by,
                                 DATE_FORMAT(created_at, '%Y-%m-%d %H:%i'), id IS NOT NULL)
                FROM payment_allocations ORDER BY payment_id""", String.class))
                .containsExactly(account + " " + invoice + " 40.00 " + user + " 2026-10-30 09:15 1",
                        counter + " " + receipt + " 12.00 " + user + " 2026-10-30 09:15 1");
        assertThat(db.queryForObject(balance, BigDecimal.class, customer, customer)).isEqualByComparingTo(before)
                .isEqualByComparingTo("40.00");

        assertThat(db.queryForObject("SELECT (SELECT COUNT(*) FROM supplier_invoices)"
                + " + (SELECT COUNT(*) FROM supplier_credit_allocations) + (SELECT COUNT(*) FROM supplier_payments)"
                + " + (SELECT COUNT(*) FROM supplier_payment_allocations)", Integer.class)).isZero();
        db.update("""
                INSERT INTO payment_allocations (payment_id, invoice_id, amount, created_at, created_by)
                VALUES (?, ?, 20.00, UTC_TIMESTAMP(6), ?)""", account, invoice, user);
        assertThat(db.queryForObject("SELECT SUM(amount) FROM payment_allocations WHERE payment_id = ?",
                BigDecimal.class, account)).isEqualByComparingTo("60.00");
        assertThatThrownBy(() -> db.update("UPDATE payment_allocations SET amount = 1"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> db.update("DELETE FROM payment_allocations"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> db.update("""
                INSERT INTO payment_allocations (payment_id, invoice_id, amount, created_at, created_by)
                VALUES (?, ?, 1.00, UTC_TIMESTAMP(6), NULL)""", account, invoice))
                .isInstanceOf(DataAccessException.class);
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(upgraded).locations("classpath:db/migration").target(target).load();
    }
}
