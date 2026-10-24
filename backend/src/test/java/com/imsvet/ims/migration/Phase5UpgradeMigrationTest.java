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
 * Migration test (M) on a seeded database (database-migration.md §9; the empty-database case is
 * {@link BaselineMigrationTest}): a separate schema is migrated to the end of Phase 4 (V04_009), filled with Phase 4
 * data — users, a product, lots with their ledger, a pending reorder proposal — then upgraded with every Phase 5
 * migration. Nothing fails, the data stay as they were (balances equal the ledger), the new nullable columns are
 * empty, and the new constraints apply (receipt_cancellation negative only, receipt.cancel granted).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class Phase5UpgradeMigrationTest {

    private static final String SCHEMA = "ims_upgrade_check";

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
    void phase5MigrationsUpgradeADatabaseHoldingPhase4Data() {
        flyway("04.009").migrate();
        JdbcTemplate db = new JdbcTemplate(upgraded);
        db.update("""
                INSERT INTO users (username, password_hash, full_name, role_id, is_active, must_change_password, created_at)
                SELECT 'upgrade-user', 'not-a-login', 'Upgrade user', id, TRUE, FALSE, UTC_TIMESTAMP(6)
                FROM roles WHERE name = 'Pharmacist'""");
        long user = db.queryForObject("SELECT id FROM users WHERE username = 'upgrade-user'", Long.class);
        db.update("""
                INSERT INTO products (sku, commercial_name, base_unit, is_divisible, vat_rate, reorder_point, max_stock,
                                      created_at, updated_at)
                VALUES ('UPG-1', 'Upgradeval', 'tablet', FALSE, 9.00, 10, 50, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""");
        long product = db.queryForObject("SELECT id FROM products WHERE sku = 'UPG-1'", Long.class);
        db.update("""
                INSERT INTO lots (product_id, lot_number, expiry_date, unit_cost, quantity_on_hand, status, received_at)
                VALUES (?, 'UPG-L1', '2027-05-31', 0.3000, 8, 'active', UTC_TIMESTAMP(6)),
                       (?, 'UPG-L2', '2027-06-30', 0.3500, 0, 'blocked', UTC_TIMESTAMP(6))""", product, product);
        long lot = db.queryForObject("SELECT id FROM lots WHERE lot_number = 'UPG-L1'", Long.class);
        db.update("""
                INSERT INTO stock_movements (lot_id, product_id, movement_type, quantity, reason, user_id, created_at)
                VALUES (?, ?, 'opening_stock', 10, NULL, ?, UTC_TIMESTAMP(6)),
                       (?, ?, 'adjustment_breakage', -2, 'Broken', ?, UTC_TIMESTAMP(6))""",
                lot, product, user, lot, product, user);
        db.update("""
                INSERT INTO reorder_proposals (product_id, current_qty, suggested_qty, status, created_at, updated_at)
                VALUES (?, 8, 42, 'pending', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", product);

        flyway(null).migrate();

        MigrationInfo[] applied = flyway(null).info().applied();
        assertThat(applied).allSatisfy(m -> assertThat(m.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(applied[applied.length - 1].getVersion().getVersion()).isEqualTo("05.016");
        assertThat(flyway(null).info().pending()).isEmpty();
        assertThat(db.queryForList("""
                SELECT CONCAT(l.lot_number, ' ', CAST(l.quantity_on_hand AS CHAR), ' ', l.status, ' ',
                              COALESCE(l.supplier_id, 'none'), ' ', CAST(COALESCE(SUM(m.quantity), 0) AS CHAR))
                FROM lots l LEFT JOIN stock_movements m ON m.lot_id = l.id
                GROUP BY l.id ORDER BY l.lot_number""", String.class))
                .containsExactly("UPG-L1 8.000 active none 8.000", "UPG-L2 0.000 blocked none 0.000");
        assertThat(db.queryForMap("SELECT status, supplier_id FROM reorder_proposals WHERE product_id = ?", product))
                .containsEntry("status", "pending").containsEntry("supplier_id", null);
        db.update("""
                INSERT INTO stock_movements (lot_id, product_id, movement_type, quantity, user_id, created_at)
                VALUES (?, ?, 'receipt_cancellation', -1, ?, UTC_TIMESTAMP(6))""", lot, product, user);
        assertThatThrownBy(() -> db.update("""
                INSERT INTO stock_movements (lot_id, product_id, movement_type, quantity, user_id, created_at)
                VALUES (?, ?, 'receipt_cancellation', 1, ?, UTC_TIMESTAMP(6))""", lot, product, user))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_stock_movements_sign");
        assertThat(db.queryForList("""
                SELECT r.name FROM role_permissions rp JOIN roles r ON r.id = rp.role_id
                JOIN permissions p ON p.id = rp.permission_id WHERE p.code = 'receipt.cancel' ORDER BY r.name""",
                String.class)).containsExactly("Administrator", "Pharmacist");
    }

    /** {@code target} null: every migration. */
    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(upgraded).locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }
}
