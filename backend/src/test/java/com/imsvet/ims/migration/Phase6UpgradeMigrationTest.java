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
 * Phase 5 (V05_016), filled with Phase 5 data — a user, a supplier, a product, lots of that supplier with their
 * ledger, a document sequence — then upgraded with every Phase 6 migration (to V06_003). Nothing fails, the data stay
 * as they were (balances equal the ledger, the sequence keeps its number), the customer tables start empty, and their
 * rules apply on the upgraded data: a new customer is cash only (credit limit 0.00, NULL allowed, negative refused),
 * a customer price is > 0 and keeps its product from being deleted, a category discount stays within 0–100, and
 * customer.credit.manage stays with the Accountant and the Administrator. Fictional data only (ADR-114).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class Phase6UpgradeMigrationTest {

    private static final String SCHEMA = "ims_upgrade_check_6";

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
    void phase6MigrationsUpgradeADatabaseHoldingPhase5Data() {
        flyway("05.016").migrate();
        JdbcTemplate db = new JdbcTemplate(upgraded);
        db.update("""
                INSERT INTO users (username, password_hash, full_name, role_id, is_active, must_change_password, created_at)
                SELECT 'upgrade-user', 'not-a-login', 'Upgrade user', id, TRUE, FALSE, UTC_TIMESTAMP(6)
                FROM roles WHERE name = 'Pharmacist'""");
        long user = db.queryForObject("SELECT id FROM users WHERE username = 'upgrade-user'", Long.class);
        db.update("""
                INSERT INTO suppliers (name, supplier_kind, payment_terms_days, created_at, updated_at)
                VALUES ('Upgrade supplier', 'wholesaler', 30, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""");
        long supplier = db.queryForObject("SELECT id FROM suppliers WHERE name = 'Upgrade supplier'", Long.class);
        db.update("""
                INSERT INTO products (sku, commercial_name, base_unit, is_divisible, vat_rate, sale_price, created_at,
                                      updated_at)
                VALUES ('UPG6-1', 'Upgradeval six', 'tablet', FALSE, 9.00, 0.5000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""");
        long product = db.queryForObject("SELECT id FROM products WHERE sku = 'UPG6-1'", Long.class);
        db.update("""
                INSERT INTO lots (product_id, lot_number, expiry_date, supplier_id, unit_cost, quantity_on_hand, status,
                                  received_at)
                VALUES (?, 'UPG6-L1', '2027-05-31', ?, 0.3000, 7, 'active', UTC_TIMESTAMP(6)),
                       (?, 'UPG6-L2', '2027-06-30', NULL, 0.3500, 4, 'blocked', UTC_TIMESTAMP(6))""",
                product, supplier, product);
        long lot1 = db.queryForObject("SELECT id FROM lots WHERE lot_number = 'UPG6-L1'", Long.class);
        long lot2 = db.queryForObject("SELECT id FROM lots WHERE lot_number = 'UPG6-L2'", Long.class);
        db.update("""
                INSERT INTO stock_movements (lot_id, product_id, movement_type, quantity, reason, user_id, created_at)
                VALUES (?, ?, 'opening_stock', 10, NULL, ?, UTC_TIMESTAMP(6)),
                       (?, ?, 'receipt_cancellation', -3, NULL, ?, UTC_TIMESTAMP(6)),
                       (?, ?, 'opening_stock', 4, NULL, ?, UTC_TIMESTAMP(6))""",
                lot1, product, user, lot1, product, user, lot2, product, user);
        db.update("INSERT INTO document_sequences (doc_key, year, last_number) VALUES ('PO', 2026, 41)");

        flyway("06.003").migrate();

        MigrationInfo[] applied = flyway("06.003").info().applied();
        assertThat(applied).allSatisfy(m -> assertThat(m.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(applied[applied.length - 1].getVersion().getVersion()).isEqualTo("06.003");
        assertThat(flyway("06.003").info().pending()).isEmpty();
        assertThat(db.queryForList("""
                SELECT CONCAT(l.lot_number, ' ', CAST(l.quantity_on_hand AS CHAR), ' ', l.status, ' ',
                              IF(l.supplier_id IS NULL, 'none', 'supplier'), ' ',
                              CAST(COALESCE(SUM(m.quantity), 0) AS CHAR))
                FROM lots l LEFT JOIN stock_movements m ON m.lot_id = l.id
                GROUP BY l.id ORDER BY l.lot_number""", String.class))
                .containsExactly("UPG6-L1 7.000 active supplier 7.000", "UPG6-L2 4.000 blocked none 4.000");
        assertThat(db.queryForObject("SELECT last_number FROM document_sequences WHERE doc_key = 'PO'", Integer.class))
                .isEqualTo(41);
        assertThat(db.queryForObject("""
                SELECT (SELECT COUNT(*) FROM customers) + (SELECT COUNT(*) FROM customer_categories)
                     + (SELECT COUNT(*) FROM customer_product_prices)""", Integer.class)).isZero();

        db.update("""
                INSERT INTO customer_categories (name, discount_pct, created_at, updated_at)
                VALUES ('Upgrade breeders', 5.00, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""");
        assertThatThrownBy(() -> db.update("""
                INSERT INTO customer_categories (name, discount_pct, created_at, updated_at)
                VALUES ('Upgrade too much', 100.01, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))"""))
                .isInstanceOf(DataAccessException.class);
        db.update("""
                INSERT INTO customers (name, phone, phone_key, created_at, updated_at)
                VALUES ('Upgrade farm', '0100 00 06 01', '100000601', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""");
        long customer = db.queryForObject("SELECT id FROM customers WHERE name = 'Upgrade farm'", Long.class);
        assertThat(db.queryForMap("SELECT customer_type, CAST(credit_limit AS CHAR) AS credit_limit FROM customers"
                + " WHERE id = ?", customer)).containsEntry("customer_type", "breeder")
                .containsEntry("credit_limit", "0.00");
        db.update("UPDATE customers SET credit_limit = NULL WHERE id = ?", customer);
        assertThatThrownBy(() -> db.update("UPDATE customers SET credit_limit = -1 WHERE id = ?", customer))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_customers_credit_limit");
        assertThatThrownBy(() -> db.update("""
                INSERT INTO customer_product_prices (customer_id, product_id, unit_price, created_at, updated_at)
                VALUES (?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", customer, product))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_customer_product_prices_price");
        db.update("""
                INSERT INTO customer_product_prices (customer_id, product_id, unit_price, created_at, updated_at)
                VALUES (?, ?, 0.4200, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", customer, product);
        assertThatThrownBy(() -> db.update("DELETE FROM products WHERE id = ?", product))
                .isInstanceOf(DataAccessException.class);
        assertThat(db.queryForList("""
                SELECT r.name FROM role_permissions rp JOIN roles r ON r.id = rp.role_id
                JOIN permissions p ON p.id = rp.permission_id WHERE p.code = 'customer.credit.manage' ORDER BY r.name""",
                String.class)).containsExactly("Accountant", "Administrator");
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(upgraded).locations("classpath:db/migration").target(target).load();
    }
}
