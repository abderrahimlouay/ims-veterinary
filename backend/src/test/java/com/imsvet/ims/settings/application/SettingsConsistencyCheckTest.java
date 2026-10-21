package com.imsvet.ims.settings.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-044: the seeded settings match the definitions; a malformed or unknown row is reported (and stops the
 * start-up).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SettingsConsistencyCheckTest {

    @Autowired
    private SettingsConsistencyCheck check;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Test
    void seededSettingsMatchTheDefinitions() {
        assertThat(check.problems()).isEmpty();
    }

    @Test
    void reportsMalformedAndUnknownSettings() {
        jdbc.update("UPDATE app_settings SET setting_value = 'nine' WHERE setting_key = 'default_vat_rate'");
        jdbc.update("""
                INSERT INTO app_settings (setting_key, setting_value, updated_at)
                VALUES ('legacy_key', 'x', UTC_TIMESTAMP(6))""");
        entityManager.clear();

        assertThat(check.problems()).containsExactlyInAnyOrder("malformed default_vat_rate", "unknown legacy_key");
    }
}
