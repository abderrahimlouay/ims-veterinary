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
 * Phase 10 migrations (database-migration.md §10) on a database holding Phase 9 data: customers keep their data with
 * empty NIS, RC and AI (SD-148); the new `company_profiles` starts empty and append-only (ADR-140, SD-147).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class Phase10UpgradeMigrationTest {

    private static final String SCHEMA = "ims_upgrade_check_10";
    private static final String LAST = "10.002";

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
    void phase10MigrationsUpgradeADatabaseHoldingPhase9Data() {
        flyway("09.007").migrate();
        JdbcTemplate db = new JdbcTemplate(upgraded);
        db.update("""
                INSERT INTO users (username, password_hash, full_name, role_id, is_active, must_change_password, created_at)
                SELECT 'upgrade-user', 'not-a-login', 'Upgrade user', id, TRUE, FALSE, UTC_TIMESTAMP(6)
                FROM roles WHERE name = 'Administrator'""");
        long user = db.queryForObject("SELECT id FROM users WHERE username = 'upgrade-user'", Long.class);
        db.update("""
                INSERT INTO customers (name, tax_id, created_at, updated_at)
                VALUES ('Ferme Mise à niveau', 'NIFUPGRADE10', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""");

        flyway(LAST).migrate();

        MigrationInfo[] applied = flyway(LAST).info().applied();
        assertThat(applied).allSatisfy(m -> assertThat(m.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(applied[applied.length - 1].getVersion().getVersion()).isEqualTo(LAST);
        assertThat(db.queryForList("""
                SELECT CONCAT_WS('|', name, tax_id, COALESCE(nis, '-'), COALESCE(rc, '-'), COALESCE(ai, '-'))
                FROM customers""", String.class)).containsExactly("Ferme Mise à niveau|NIFUPGRADE10|-|-|-");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM company_profiles", Integer.class)).isZero();

        db.update("""
                INSERT INTO company_profiles (legal_name, nif, effective_from, created_by)
                VALUES ('Pharmacie Mise à niveau', 'NIF-1', UTC_TIMESTAMP(6), ?)""", user);
        assertThatThrownBy(() -> db.update("UPDATE company_profiles SET nif = 'NIF-2'"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> db.update("DELETE FROM company_profiles"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(upgraded).locations("classpath:db/migration").target(target).load();
    }
}
