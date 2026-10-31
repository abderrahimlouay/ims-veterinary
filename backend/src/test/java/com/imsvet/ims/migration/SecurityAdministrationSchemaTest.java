package com.imsvet.ims.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.MigrationAccount;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Migration test (M) for the Phase 1 schema (database.md §3.1): tables, append-only triggers (ADR-017)
 * and the settings seed (ADR-038, ADR-044).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SecurityAdministrationSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    /** Trigger metadata is visible only with the TRIGGER privilege, which the application account lacks (ADR-087). */
    @Autowired
    private MigrationAccount migration;

    @Test
    void phase1TablesExist() {
        assertThat(jdbc.queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()", String.class))
                .contains("roles", "permissions", "role_permissions", "users", "audit_log", "app_settings",
                        "SPRING_SESSION", "SPRING_SESSION_ATTRIBUTES");
    }

    @Test
    void auditLogHasUpdateAndDeleteGuards() {
        assertThat(migration.jdbc().queryForList("""
                SELECT CONCAT(TRIGGER_NAME, ':', EVENT_MANIPULATION) FROM information_schema.TRIGGERS
                WHERE TRIGGER_SCHEMA = DATABASE() AND EVENT_OBJECT_TABLE = 'audit_log'""", String.class))
                .containsExactlyInAnyOrder("trg_audit_log_no_update:UPDATE", "trg_audit_log_no_delete:DELETE");
    }

    @Test
    void settingsAreSeeded() {
        Map<String, String> settings = jdbc.queryForList("SELECT setting_key, setting_value FROM app_settings")
                .stream().collect(Collectors.toMap(r -> (String) r.get("setting_key"),
                        r -> (String) r.get("setting_value")));

        assertThat(settings).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("expiry_alert_days", "60,30,15"),
                Map.entry("default_vat_rate", "9.00"),
                Map.entry("login_max_failures", "5"),
                Map.entry("login_window_minutes", "15"),
                Map.entry("login_lockout_minutes", "15"),
                Map.entry("currency", "DZD"),
                Map.entry("locale", "fr-DZ"),
                Map.entry("business_time_zone", "Africa/Algiers"),
                Map.entry("opening_stock_open", "true"),
                Map.entry("reorder_job_time", "06:00"),
                Map.entry("integrity_check_time", "02:00"),
                Map.entry("credit_overdue_block_days", "off")));
    }
}
