package com.imsvet.ims.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.MySqlContainerConfiguration;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.MigrationAccount;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-087, BR-SEC-006: the application connects with a restricted account; Flyway ran with the migration account,
 * whose callback granted the application account its rights on every table: no {@code UPDATE}/{@code DELETE} on the
 * append-only tables and no DDL anywhere. ADR-012: application connections wait at most 5 s for a row lock.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DatabaseAccountsTest {

    @Autowired
    private JdbcTemplate app;

    @Autowired
    private MigrationAccount migration;

    @Test
    void theApplicationUsesTheRestrictedAccount() {
        assertThat(app.queryForObject("SELECT CURRENT_USER()", String.class))
                .startsWith(MySqlContainerConfiguration.APP_USER + "@");
        assertThat(migration.jdbc().queryForObject("SELECT CURRENT_USER()", String.class))
                .startsWith(MySqlContainerConfiguration.MIGRATION_USER + "@");
    }

    @Test
    void theApplicationAccountCannotChangeOrRemoveAuditRows() {
        assertDenied(() -> app.update("UPDATE audit_log SET action = 'TAMPERED' WHERE id = -1"));
        assertDenied(() -> app.update("DELETE FROM audit_log WHERE id = -1"));
        assertThat(app.queryForObject("SELECT COUNT(*) >= 0 FROM audit_log", Boolean.class)).isTrue();
    }

    @Test
    void theApplicationAccountHasNoDdl() {
        assertDenied(() -> app.execute("CREATE TABLE intruder (id INT)"));
        assertDenied(() -> app.execute("ALTER TABLE products ADD COLUMN intruder INT"));
        assertDenied(() -> app.execute("DROP TABLE app_settings"));
    }

    /** {@code table_privileges} shows only the connected account's own rows: the application account reads its own. */
    @Test
    void everyApplicationTableIsGrantedAndOnlyAppendOnlyTablesLackUpdate() {
        List<String> tables = migration.jdbc().queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'""",
                String.class);
        String grantee = "'" + MySqlContainerConfiguration.APP_USER + "'@'%'";

        for (String table : tables) {
            List<String> privileges = app.queryForList("""
                    SELECT privilege_type FROM information_schema.table_privileges
                    WHERE table_schema = DATABASE() AND table_name = ? AND grantee = ?""", String.class, table, grantee);
            if (List.of("audit_log", "stock_movements").contains(table)) {
                assertThat(privileges).as(table).containsExactlyInAnyOrder("SELECT", "INSERT");
            } else {
                assertThat(privileges).as(table).containsExactlyInAnyOrder("SELECT", "INSERT", "UPDATE", "DELETE");
            }
        }
        assertThat(tables).contains("audit_log", "products", "lots", "users");
    }

    @Test
    void applicationConnectionsWaitAtMostFiveSecondsForALock() {
        assertThat(app.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class)).isEqualTo(5);
    }

    /** MySQL error 1142: command denied to user for table. */
    private static void assertDenied(Runnable statement) {
        assertThatThrownBy(statement::run).isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(((DataAccessException) e).getMostSpecificCause())
                        .isInstanceOfSatisfying(SQLException.class, sql -> assertThat(sql.getErrorCode()).isEqualTo(1142)));
    }
}
