package com.imsvet.ims.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.MySqlContainerConfiguration;
import com.imsvet.ims.TestcontainersConfiguration;
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
 * Phase 6 (V06_003), filled with Phase 6 data, then upgraded with every Phase 7 migration (to V07_012). Nothing fails,
 * the data stay as they were (balances equal the ledger, the sequence, the customer's limit), the sales and cash tables
 * start empty, and their rules apply on the upgraded data: a draft has no number and a confirmed document needs one, a
 * credit sale needs a customer, sale_cancellation is positive only, payments are append-only, the overdue setting is off
 * and the Pharmacist may open a session (ADR-126). Fictional data only (ADR-114).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class Phase7UpgradeMigrationTest {

    private static final String SCHEMA = "ims_upgrade_check_7";

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
    void phase7MigrationsUpgradeADatabaseHoldingPhase6Data() {
        flyway("06.003").migrate();
        JdbcTemplate db = new JdbcTemplate(upgraded);
        db.update("""
                INSERT INTO users (username, password_hash, full_name, role_id, is_active, must_change_password, created_at)
                SELECT 'upgrade-user', 'not-a-login', 'Upgrade user', id, TRUE, FALSE, UTC_TIMESTAMP(6)
                FROM roles WHERE name = 'Pharmacist'""");
        long user = db.queryForObject("SELECT id FROM users WHERE username = 'upgrade-user'", Long.class);
        db.update("""
                INSERT INTO products (sku, commercial_name, base_unit, is_divisible, vat_rate, sale_price, created_at,
                                      updated_at)
                VALUES ('UPG7-1', 'Upgradeval sept', 'tablet', FALSE, 9.00, 0.5000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""");
        long product = db.queryForObject("SELECT id FROM products WHERE sku = 'UPG7-1'", Long.class);
        db.update("""
                INSERT INTO lots (product_id, lot_number, expiry_date, unit_cost, quantity_on_hand, status, received_at)
                VALUES (?, 'UPG7-L1', '2027-05-31', 0.3000, 7, 'active', UTC_TIMESTAMP(6))""", product);
        long lot = db.queryForObject("SELECT id FROM lots WHERE lot_number = 'UPG7-L1'", Long.class);
        db.update("""
                INSERT INTO stock_movements (lot_id, product_id, movement_type, quantity, reason, user_id, created_at)
                VALUES (?, ?, 'opening_stock', 10, NULL, ?, UTC_TIMESTAMP(6)),
                       (?, ?, 'receipt_cancellation', -3, NULL, ?, UTC_TIMESTAMP(6))""",
                lot, product, user, lot, product, user);
        db.update("INSERT INTO document_sequences (doc_key, year, last_number) VALUES ('PO', 2026, 41)");
        db.update("""
                INSERT INTO customers (name, phone, phone_key, credit_limit, created_at, updated_at)
                VALUES ('Upgrade farm', '0100 00 07 01', '100000701', 500.00, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""");
        long customer = db.queryForObject("SELECT id FROM customers WHERE name = 'Upgrade farm'", Long.class);

        flyway("07.012").migrate();

        MigrationInfo[] applied = flyway("07.012").info().applied();
        assertThat(applied).allSatisfy(m -> assertThat(m.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(applied[applied.length - 1].getVersion().getVersion()).isEqualTo("07.012");
        assertThat(db.queryForObject("""
                SELECT CONCAT(CAST(l.quantity_on_hand AS CHAR), ' ', CAST(SUM(m.quantity) AS CHAR))
                FROM lots l JOIN stock_movements m ON m.lot_id = l.id WHERE l.id = ? GROUP BY l.id""", String.class, lot))
                .isEqualTo("7.000 7.000");
        assertThat(db.queryForObject("SELECT last_number FROM document_sequences WHERE doc_key = 'PO'", Integer.class))
                .isEqualTo(41);
        assertThat(db.queryForObject("SELECT CAST(credit_limit AS CHAR) FROM customers WHERE id = ?", String.class, customer))
                .isEqualTo("500.00");
        assertThat(db.queryForObject("""
                SELECT (SELECT COUNT(*) FROM sales_documents) + (SELECT COUNT(*) FROM sales_document_lines)
                     + (SELECT COUNT(*) FROM cash_sessions) + (SELECT COUNT(*) FROM cash_movements)
                     + (SELECT COUNT(*) FROM customer_payments) + (SELECT COUNT(*) FROM payment_allocations)""",
                Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT setting_value FROM app_settings WHERE setting_key = 'credit_overdue_block_days'",
                String.class)).isEqualTo("off");
        assertThat(db.queryForList("""
                SELECT r.name FROM role_permissions rp JOIN roles r ON r.id = rp.role_id
                JOIN permissions p ON p.id = rp.permission_id WHERE p.code = 'cash.open'""", String.class))
                .contains("Pharmacist");

        String document = """
                INSERT INTO sales_documents (doc_type, number, status, customer_id, is_credit_sale, created_by, created_at,
                                             updated_at)
                VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""";
        db.update(document, "invoice", null, "draft", customer, true, user);
        assertThatThrownBy(() -> db.update(document, "invoice", "INV-1", "draft", customer, false, user))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_sales_documents_draft_number");
        assertThatThrownBy(() -> db.update(document, "invoice", null, "confirmed", customer, false, user))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_sales_documents_number");
        assertThatThrownBy(() -> db.update(document, "invoice", null, "draft", null, true, user))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_sales_documents_credit_customer");

        String movement = """
                INSERT INTO stock_movements (lot_id, product_id, movement_type, quantity, reason, user_id, created_at)
                VALUES (?, ?, 'sale_cancellation', ?, NULL, ?, UTC_TIMESTAMP(6))""";
        db.update(movement, lot, product, 1, user);
        assertThatThrownBy(() -> db.update(movement, lot, product, -1, user))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_stock_movements_sign");

        db.update("""
                INSERT INTO customer_payments (customer_id, method, amount, reference, paid_at, created_by)
                VALUES (?, 'cheque', 10.00, 'CHQ', UTC_TIMESTAMP(6), ?)""", customer, user);
        assertThatThrownBy(() -> db.update("UPDATE customer_payments SET amount = 1"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(upgraded).locations("classpath:db/migration").target(target).load();
    }
}
