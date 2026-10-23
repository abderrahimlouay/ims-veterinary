package com.imsvet.ims.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.MigrationAccount;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Repository test (R) for BR-AUDIT-002: the database itself refuses to change or remove audit rows (triggers of
 * ADR-017, SD-102) even to the migration account, which holds every right on the schema; the application account is
 * stopped earlier, by its grants (ADR-087, {@code DatabaseAccountsTest}). The probe rows are committed through the
 * migration account (the trigger must see them) and, being undeletable, use their own {@code entity} value.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditLogAppendOnlyTest {

    private static final String PROBE = "append_only_probe";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MigrationAccount migration;

    private long id;

    @BeforeEach
    void insertEntry() {
        id = migration.jdbc().execute((Connection c) -> {
            try (var statement = c.prepareStatement("""
                    INSERT INTO audit_log (user_id, action, entity, entity_id, old_values, new_values, ip_address,
                                           created_at)
                    VALUES (NULL, 'SETTING_CHANGE', ?, NULL, '{"value": "60,30,15"}', '{"value": "90,30,15"}',
                            '192.0.2.10', UTC_TIMESTAMP(6))""", Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, PROBE);
                statement.executeUpdate();
                try (var keys = statement.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    @Test
    void updateIsRefusedEvenToThePrivilegedAccount() {
        assertThatThrownBy(() -> migration.jdbc().update("UPDATE audit_log SET action = 'TAMPERED' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .satisfies(AuditLogAppendOnlyTest::isAppendOnlySignal);

        assertThat(jdbc.queryForObject("SELECT action FROM audit_log WHERE id = ?", String.class, id))
                .isEqualTo("SETTING_CHANGE");
    }

    @Test
    void deleteIsRefusedEvenToThePrivilegedAccount() {
        assertThatThrownBy(() -> migration.jdbc().update("DELETE FROM audit_log WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .satisfies(AuditLogAppendOnlyTest::isAppendOnlySignal);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void entryKeepsItsJsonValues() {
        assertThat(jdbc.queryForObject("SELECT new_values->>'$.value' FROM audit_log WHERE id = ?", String.class, id))
                .isEqualTo("90,30,15");
    }

    private static void isAppendOnlySignal(Throwable thrown) {
        assertThat(((DataAccessException) thrown).getMostSpecificCause())
                .isInstanceOfSatisfying(SQLException.class, sql -> {
                    assertThat(sql.getSQLState()).isEqualTo("45000");
                    assertThat(sql.getMessage()).contains("append-only");
                });
    }
}
