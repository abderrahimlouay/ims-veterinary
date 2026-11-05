package com.imsvet.ims.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.MySqlContainerConfiguration;
import com.imsvet.ims.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Phase 11 migrations (database-migration.md §10) on a database holding Phase 10 data: the report views read the
 * existing documents, lots, supplier invoices and cash sessions (SD-149…154); the settings `ageing_buckets`,
 * `backup_time`, `backup_retention_count` and `backup_download_reminder_days` are seeded; `backup_log` starts empty
 * (SD-155). No existing row changes.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class Phase11UpgradeMigrationTest {

    private static final String SCHEMA = "ims_upgrade_check_11";
    private static final String LAST = "11.010";

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
    void phase11MigrationsReadTheExistingPhase10Data() {
        flyway("10.002").migrate();
        JdbcTemplate db = new JdbcTemplate(upgraded);
        db.update("""
                INSERT INTO users (username, password_hash, full_name, role_id, is_active, must_change_password, created_at)
                SELECT 'upgrade-user', 'not-a-login', 'Caissier Mise à niveau', id, TRUE, FALSE, UTC_TIMESTAMP(6)
                FROM roles WHERE name = 'Administrator'""");
        long user = id(db, "SELECT id FROM users WHERE username = 'upgrade-user'");
        db.update("""
                INSERT INTO products (sku, commercial_name, base_unit, is_divisible, vat_rate, purchase_price, sale_price,
                    created_at, updated_at)
                VALUES ('UP-11', 'Produit mise à niveau', 'tablet', FALSE, 9.00, 40, 100, UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6))""");
        long product = id(db, "SELECT id FROM products WHERE sku = 'UP-11'");
        db.update("""
                INSERT INTO lots (product_id, lot_number, expiry_date, unit_cost, quantity_on_hand, status, received_at)
                VALUES (?, 'LOT-UP-11', '2027-12-31', 40, 8, 'active', '2026-10-01 08:00:00')""", product);
        long lot = id(db, "SELECT id FROM lots WHERE lot_number = 'LOT-UP-11'");
        db.update("""
                INSERT INTO customers (name, customer_type, credit_limit, wilaya, created_at, updated_at)
                VALUES ('Ferme Phase 11', 'breeder', NULL, 'Djelfa', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""");
        long customer = id(db, "SELECT id FROM customers WHERE name = 'Ferme Phase 11'");
        db.update("""
                INSERT INTO sales_documents (doc_type, status, customer_id, is_credit_sale, due_date, created_by,
                    created_at, updated_at)
                VALUES ('invoice', 'draft', ?, TRUE, '2026-10-01', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                customer, user);
        long invoice = id(db, "SELECT id FROM sales_documents WHERE customer_id = " + customer);
        db.update("""
                INSERT INTO sales_document_lines (document_id, product_id, lot_id, quantity, quantity_base, unit_price,
                    unit_cost, discount_pct, vat_rate)
                VALUES (?, ?, ?, 2, 2, 100, 40, 0, 9.00)""", invoice, product, lot);
        db.update("""
                UPDATE sales_documents SET status = 'confirmed', number = 'FA-UP-11', issued_at = '2026-10-05 10:00:00',
                    total_ht = 200.00, total_vat = 18.00, total_ttc = 218.00 WHERE id = ?""", invoice);
        db.update("""
                INSERT INTO stock_movements (lot_id, product_id, movement_type, quantity, user_id, created_at)
                VALUES (?, ?, 'opening_stock', 10, ?, '2026-10-01 08:00:00')""", lot, product, user);
        db.update("""
                INSERT INTO stock_movements (lot_id, product_id, movement_type, quantity, reference_type, reference_id,
                    user_id, created_at)
                VALUES (?, ?, 'sale', -2, 'sales_document', ?, ?, '2026-10-05 10:00:00')""", lot, product, invoice,
                user);
        db.update("INSERT INTO suppliers (name, created_at, updated_at) VALUES ('Labo Phase 11', UTC_TIMESTAMP(6), "
                + "UTC_TIMESTAMP(6))");
        long supplier = id(db, "SELECT id FROM suppliers WHERE name = 'Labo Phase 11'");
        db.update("""
                INSERT INTO supplier_invoices (supplier_id, invoice_number, invoice_date, due_date, total_ttc, status,
                    created_by, created_at)
                VALUES (?, 'FA-LABO-1', '2026-09-10', '2026-10-10', 500.00, 'recorded', ?, UTC_TIMESTAMP(6))""",
                supplier, user);
        db.update("""
                INSERT INTO cash_sessions (cashier_id, status, opened_at, closed_at, closed_by, opening_amount,
                    expected_amount, counted_amount)
                VALUES (?, 'closed', '2026-10-05 07:00:00', '2026-10-05 18:00:00', ?, 10.00, 10.00, 8.00)""", user,
                user);
        String before = fingerprint(db);

        flyway(LAST).migrate();

        MigrationInfo[] applied = flyway(LAST).info().applied();
        assertThat(applied).allSatisfy(m -> assertThat(m.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(applied[applied.length - 1].getVersion().getVersion()).isEqualTo(LAST);
        assertThat(fingerprint(db)).isEqualTo(before);

        Map<String, Object> margin = db.queryForMap("""
                SELECT SUM(revenue_ht) AS revenue, SUM(cost) AS cost, MAX(customer_name) AS customer
                FROM v_sales_margin WHERE document_id = ?""", invoice);
        assertThat((BigDecimal) margin.get("revenue")).isEqualByComparingTo("200.00");
        assertThat((BigDecimal) margin.get("cost")).isEqualByComparingTo("80.00");
        assertThat(margin.get("customer")).isEqualTo("Ferme Phase 11");
        assertThat(db.queryForObject("SELECT amount FROM v_customer_receivables WHERE item_id = ? "
                + "AND item_type = 'invoice'", BigDecimal.class, invoice)).isEqualByComparingTo("218.00");
        assertThat(db.queryForObject("SELECT open_amount FROM v_supplier_open_items WHERE supplier_id = ?",
                BigDecimal.class, supplier)).isEqualByComparingTo("500.00");
        assertThat(db.queryForObject("SELECT CONCAT(cashier_name, '|', variance_amount) FROM v_cash_sessions",
                String.class)).isEqualTo("Caissier Mise à niveau|-2.00");
        assertThat(db.queryForList("""
                SELECT CONCAT(movement_type, '|', COALESCE(document_type, '-'), '|', COALESCE(party_name, '-'))
                FROM v_lot_traceability WHERE lot_id = ? ORDER BY movement_id""", String.class, lot))
                .containsExactly("opening_stock|-|-", "sale|invoice|Ferme Phase 11");
        Map<String, Object> valuation = db.queryForMap(
                "SELECT value_at_cost, value_at_sale_price FROM v_stock_valuation WHERE lot_id = ?", lot);
        assertThat((BigDecimal) valuation.get("value_at_cost")).isEqualByComparingTo("320.00");
        assertThat((BigDecimal) valuation.get("value_at_sale_price")).isEqualByComparingTo("800.00");
        assertThat(db.queryForList("""
                SELECT CONCAT(setting_key, '=', setting_value) FROM app_settings
                WHERE setting_key IN ('ageing_buckets', 'backup_time', 'backup_retention_count',
                    'backup_download_reminder_days') ORDER BY setting_key""", String.class))
                .containsExactly("ageing_buckets=30,60,90", "backup_download_reminder_days=7",
                        "backup_retention_count=30", "backup_time=02:00");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM backup_log", Integer.class)).isZero();
    }

    /** The Phase 10 rows that Phase 11 must leave untouched. */
    private static String fingerprint(JdbcTemplate db) {
        return String.join(";", db.queryForList("""
                SELECT CONCAT_WS('|', 'doc', id, number, status, total_ttc) FROM sales_documents
                UNION ALL SELECT CONCAT_WS('|', 'lot', id, quantity_on_hand, status) FROM lots
                UNION ALL SELECT CONCAT_WS('|', 'move', id, quantity) FROM stock_movements
                UNION ALL SELECT CONCAT_WS('|', 'inv', id, total_ttc, status) FROM supplier_invoices
                UNION ALL SELECT CONCAT_WS('|', 'cash', id, variance_amount) FROM cash_sessions
                ORDER BY 1""", String.class));
    }

    private static long id(JdbcTemplate db, String sql) {
        return db.queryForObject(sql, Long.class);
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(upgraded).locations("classpath:db/migration").target(target).load();
    }
}
