package com.imsvet.ims.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.users.application.AdministratorBootstrap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * ADR-040: the first Administrator is created only into an empty users table, must change the password,
 * and the creation is audited as a system action. Integration tests never commit users, so the table is empty.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AdministratorBootstrapTest {

    @Autowired
    private AdministratorBootstrap bootstrap;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void requireAnEmptyUsersTable() {
        RequestContextHolder.resetRequestAttributes();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class))
                .as("no test may commit users").isZero();
    }

    @Test
    void createsAnAdministratorWhoMustChangeThePassword() {
        assertThat(bootstrap.createIfNoUsers("admin", "Bootstrap-secret-2026", "First Admin")).isTrue();

        Map<String, Object> user = jdbc.queryForMap("""
                SELECT u.id, u.password_hash, u.must_change_password, u.is_active, r.name AS role
                FROM users u JOIN roles r ON r.id = u.role_id WHERE u.username = 'admin'""");
        assertThat(user.get("role")).isEqualTo("Administrator");
        assertThat(user.get("must_change_password")).isEqualTo(true);
        assertThat(user.get("is_active")).isEqualTo(true);
        assertThat((String) user.get("password_hash")).startsWith("{bcrypt}$2a$12$");

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT user_id, action, new_values FROM audit_log WHERE entity = 'users' AND entity_id = ?",
                user.get("id"));
        assertThat(audit.get("action")).isEqualTo("USER_CREATE");
        assertThat(audit.get("user_id")).as("system action").isNull();
        assertThat((String) audit.get("new_values")).doesNotContain("Bootstrap-secret-2026");
    }

    @Test
    void doesNothingWhenUsersExist() {
        bootstrap.createIfNoUsers("admin", "Bootstrap-secret-2026", "First Admin");

        assertThat(bootstrap.createIfNoUsers("second", "Bootstrap-secret-2026", "Second")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
    }

    @Test
    void aPasswordViolatingThePolicyStopsTheBootstrap() {
        assertThatThrownBy(() -> bootstrap.createIfNoUsers("admin", "too-short", "First Admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("too-short");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isZero();
    }
}
