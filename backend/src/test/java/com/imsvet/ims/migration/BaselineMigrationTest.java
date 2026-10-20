package com.imsvet.ims.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Migration test (M) on an empty MySQL 8.4: Flyway baseline and the session settings of
 * database-migration.md §9 (ADR-010, ADR-013, ADR-018, ADR-024, NFR-008).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BaselineMigrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allMigrationsApplySuccessfullyOnAnEmptyDatabase() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).isNotEmpty();
        assertThat(applied).allSatisfy(m -> assertThat(m.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("00.001");
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void serverIsMysql84() {
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("8.4.");
    }

    @Test
    void databaseDefaultsToUtf8mb4AccentAndCaseInsensitiveCollation() {
        var row = jdbc.queryForMap("""
                SELECT DEFAULT_CHARACTER_SET_NAME AS cs, DEFAULT_COLLATION_NAME AS coll
                FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = DATABASE()""");

        assertThat(row.get("cs")).isEqualTo("utf8mb4");
        assertThat(row.get("coll")).isEqualTo("utf8mb4_0900_ai_ci");
    }

    @Test
    void sessionUsesStrictSqlMode() {
        String sqlMode = jdbc.queryForObject("SELECT @@SESSION.sql_mode", String.class);

        assertThat(sqlMode.split(",")).contains(
                "STRICT_TRANS_TABLES", "ONLY_FULL_GROUP_BY", "NO_ENGINE_SUBSTITUTION", "ERROR_FOR_DIVISION_BY_ZERO");
    }

    @Test
    void sessionTimeZoneIsUtc() {
        assertThat(jdbc.queryForObject("SELECT @@SESSION.time_zone", String.class)).isEqualTo("+00:00");
    }

    @Test
    void sessionIsolationIsReadCommitted() {
        assertThat(jdbc.queryForObject("SELECT @@SESSION.transaction_isolation", String.class))
                .isEqualTo("READ-COMMITTED");
    }
}
