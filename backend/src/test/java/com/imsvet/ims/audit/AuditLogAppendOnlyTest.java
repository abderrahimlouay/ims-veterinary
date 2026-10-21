package com.imsvet.ims.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.TestcontainersConfiguration;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository test (R) for BR-AUDIT-002: the database itself refuses to change or remove audit rows
 * (triggers of ADR-017, SD-102), whatever the application does.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AuditLogAppendOnlyTest {

    @Autowired
    private JdbcTemplate jdbc;

    private long id;

    @BeforeEach
    void insertEntry() {
        jdbc.update("""
                INSERT INTO audit_log (user_id, action, entity, entity_id, old_values, new_values, ip_address,
                                       created_at)
                VALUES (NULL, 'SETTING_CHANGE', 'app_settings', NULL, '{"value": "60,30,15"}',
                        '{"value": "90,30,15"}', '192.0.2.10', UTC_TIMESTAMP(6))""");
        id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Test
    void updateIsRefused() {
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET action = 'TAMPERED' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .satisfies(AuditLogAppendOnlyTest::isAppendOnlySignal);

        assertThat(jdbc.queryForObject("SELECT action FROM audit_log WHERE id = ?", String.class, id))
                .isEqualTo("SETTING_CHANGE");
    }

    @Test
    void deleteIsRefused() {
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log WHERE id = ?", id))
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
