package com.imsvet.ims.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.MySqlContainerConfiguration;
import com.imsvet.ims.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
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
 * V12_008 and V12_009 (ADR-180) on a database whose products name a manufacturer: each manufacturer still used
 * becomes a laboratory supplier, unless a supplier of that name exists (ignoring case and accents), and its products
 * take that supplier; an unused manufacturer is not carried over; the manufacturer column and list are gone.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProductSupplierUpgradeMigrationTest {

    private static final String SCHEMA = "ims_upgrade_check_product_supplier";

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
    void manufacturersInUseBecomeLaboratorySuppliersOfTheirProducts() {
        flyway("12.007").migrate();
        JdbcTemplate db = new JdbcTemplate(upgraded);
        db.update("INSERT INTO manufacturers (name, country) VALUES ('Vétoquinol', 'France'), ('Atlas Vét', NULL),"
                + " ('Unused Labs', NULL)");
        db.update("INSERT INTO suppliers (name, supplier_kind, created_at, updated_at)"
                + " VALUES ('ATLAS VET', 'wholesaler', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        long existing = id(db, "SELECT id FROM suppliers WHERE name = 'ATLAS VET'");
        product(db, "UP-V", "Vétoquinol");
        product(db, "UP-A", "Atlas Vét");
        db.update("""
                INSERT INTO products (sku, commercial_name, base_unit, is_divisible, vat_rate, purchase_price,
                    sale_price, created_at, updated_at)
                VALUES ('UP-N', 'Sans laboratoire', 'ml', TRUE, 9.00, 0, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""");

        flyway("12.009").migrate();

        assertThat(db.queryForMap("SELECT s.name, s.supplier_kind FROM products p JOIN suppliers s"
                + " ON s.id = p.supplier_id WHERE p.sku = 'UP-V'"))
                .containsEntry("name", "Vétoquinol").containsEntry("supplier_kind", "laboratory");
        assertThat(id(db, "SELECT supplier_id FROM products WHERE sku = 'UP-A'")).isEqualTo(existing);
        assertThat(db.queryForObject("SELECT supplier_id FROM products WHERE sku = 'UP-N'", Long.class)).isNull();
        assertThat(id(db, "SELECT COUNT(*) FROM suppliers")).isEqualTo(2);
        assertThat(id(db, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()"
                + " AND table_name = 'products' AND column_name = 'manufacturer_id'")).isZero();
        assertThat(id(db, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
                + " AND table_name = 'manufacturers'")).isZero();
    }

    private static void product(JdbcTemplate db, String sku, String manufacturer) {
        db.update("""
                INSERT INTO products (sku, commercial_name, manufacturer_id, base_unit, is_divisible, vat_rate,
                    purchase_price, sale_price, created_at, updated_at)
                SELECT ?, 'Produit mise à niveau', id, 'ml', TRUE, 9.00, 0, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                FROM manufacturers WHERE name = ?""", sku, manufacturer);
    }

    private static long id(JdbcTemplate db, String sql) {
        return db.queryForObject(sql, Long.class);
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(upgraded).locations("classpath:db/migration").target(target).load();
    }
}
