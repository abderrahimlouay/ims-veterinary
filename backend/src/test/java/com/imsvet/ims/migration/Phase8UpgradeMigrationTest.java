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
 * Phase 7 (V07_012), filled with Phase 7 data (a confirmed receipt whose line posted a sale), then upgraded with the
 * Phase 8 schema migrations (to V08_004). Nothing fails, the data stay as they were (the lot balance equals its ledger,
 * the receipt's line amounts and totals are unchanged, nothing is returned or credited yet), the return tables start
 * empty, and the new rules apply on the upgraded data: return_discard is negative only and needs a reason, the
 * returned quantity cannot exceed the sold one (ADR-127, ADR-130). Fictional data only (ADR-114).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class Phase8UpgradeMigrationTest {

    private static final String SCHEMA = "ims_upgrade_check_8";

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
    void phase8MigrationsUpgradeADatabaseHoldingPhase7Data() {
        flyway("07.012").migrate();
        JdbcTemplate db = new JdbcTemplate(upgraded);
        db.update("""
                INSERT INTO users (username, password_hash, full_name, role_id, is_active, must_change_password, created_at)
                SELECT 'upgrade-user', 'not-a-login', 'Upgrade user', id, TRUE, FALSE, UTC_TIMESTAMP(6)
                FROM roles WHERE name = 'Pharmacist'""");
        long user = db.queryForObject("SELECT id FROM users WHERE username = 'upgrade-user'", Long.class);
        db.update("""
                INSERT INTO products (sku, commercial_name, base_unit, is_divisible, vat_rate, sale_price, created_at,
                                      updated_at)
                VALUES ('UPG8-1', 'Upgradeval huit', 'tablet', FALSE, 19.00, 0.3350, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""");
        long product = db.queryForObject("SELECT id FROM products WHERE sku = 'UPG8-1'", Long.class);
        db.update("""
                INSERT INTO lots (product_id, lot_number, expiry_date, unit_cost, quantity_on_hand, status, received_at)
                VALUES (?, 'UPG8-L1', '2027-05-31', 0.2000, 7, 'active', UTC_TIMESTAMP(6))""", product);
        long lot = db.queryForObject("SELECT id FROM lots WHERE lot_number = 'UPG8-L1'", Long.class);
        db.update("""
                INSERT INTO sales_documents (doc_type, number, status, issued_at, total_ht, total_vat, total_ttc,
                                             created_by, created_at, updated_at)
                VALUES ('receipt', 'RCT-2026-000001', 'confirmed', UTC_TIMESTAMP(6), 1.01, 0.19, 1.20, ?,
                        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", user);
        long receipt = db.queryForObject("SELECT id FROM sales_documents WHERE number = 'RCT-2026-000001'", Long.class);
        db.update("""
                INSERT INTO sales_document_lines (document_id, product_id, lot_id, quantity, quantity_base, unit_price,
                                                  unit_cost, discount_pct, vat_rate)
                VALUES (?, ?, ?, 3, 3, 0.3350, 0.2000, 0, 19.00)""", receipt, product, lot);
        long line = db.queryForObject("SELECT id FROM sales_document_lines WHERE document_id = ?", Long.class, receipt);
        db.update("""
                INSERT INTO stock_movements (lot_id, product_id, movement_type, quantity, reference_type, reference_id,
                                             reason, user_id, created_at)
                VALUES (?, ?, 'opening_stock', 10, NULL, NULL, NULL, ?, UTC_TIMESTAMP(6)),
                       (?, ?, 'sale', -3, 'sales_document', ?, NULL, ?, UTC_TIMESTAMP(6))""",
                lot, product, user, lot, product, receipt, user);
        db.update("INSERT INTO document_sequences (doc_key, year, last_number) VALUES ('RCT', 2026, 1)");

        flyway("08.004").migrate();

        MigrationInfo[] applied = flyway("08.004").info().applied();
        assertThat(applied).allSatisfy(m -> assertThat(m.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(applied[applied.length - 1].getVersion().getVersion()).isEqualTo("08.004");
        assertThat(db.queryForObject("""
                SELECT CONCAT(CAST(l.quantity_on_hand AS CHAR), ' ', CAST(SUM(m.quantity) AS CHAR))
                FROM lots l JOIN stock_movements m ON m.lot_id = l.id WHERE l.id = ? GROUP BY l.id""", String.class, lot))
                .isEqualTo("7.000 7.000");
        assertThat(db.queryForObject("""
                SELECT CONCAT_WS(' ', CAST(line_ht AS CHAR), CAST(line_vat AS CHAR), CAST(line_ttc AS CHAR),
                                 CAST(returned_quantity_base AS CHAR), COALESCE(credited_ht, 'none'),
                                 COALESCE(credited_vat, 'none'))
                FROM sales_document_lines WHERE id = ?""", String.class, line))
                .isEqualTo("1.01 0.19 1.20 0.000 none none");
        assertThat(db.queryForObject("SELECT CAST(total_ttc AS CHAR) FROM sales_documents WHERE id = ?", String.class,
                receipt)).isEqualTo("1.20");
        assertThat(db.queryForObject("SELECT last_number FROM document_sequences WHERE doc_key = 'RCT'", Integer.class))
                .isEqualTo(1);
        assertThat(db.queryForObject(
                "SELECT (SELECT COUNT(*) FROM customer_returns) + (SELECT COUNT(*) FROM customer_return_lines)",
                Integer.class)).isZero();

        String movement = """
                INSERT INTO stock_movements (lot_id, product_id, movement_type, quantity, reason, user_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))""";
        db.update(movement, lot, product, "customer_return", 1, null, user);
        db.update(movement, lot, product, "return_discard", -1, "opened", user);
        assertThatThrownBy(() -> db.update(movement, lot, product, "return_discard", 1, "opened", user))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_stock_movements_sign");
        assertThatThrownBy(() -> db.update(movement, lot, product, "return_discard", -1, null, user))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_stock_movements_return_discard_reason");
        assertThatThrownBy(() -> db.update(
                "UPDATE sales_document_lines SET returned_quantity_base = 4 WHERE id = ?", line))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_sales_document_lines_returned");
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(upgraded).locations("classpath:db/migration").target(target).load();
    }
}
