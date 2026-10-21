package com.imsvet.ims.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * ADR-039 / BR-SEC-003: own password change, policy, bcrypt cost 12, forced change after a temporary password.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PasswordChangeTest {

    private static final String NEW_PASSWORD = "Quiet-harbour-lantern-7";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    @Autowired
    private EntityManager entityManager;

    private TestUsers users;
    private ApiClient client;

    @BeforeEach
    void setUp() {
        clock.reset();
        users = new TestUsers(jdbc, encoder);
        client = ApiClient.create(context, "198.51.100.20");
    }

    @Test
    void temporaryPasswordAllowsOnlyProfileChangeAndLogoutUntilChanged() throws Exception {
        TestUser admin = users.create("Administrator", true, true);
        client.login(admin.username(), admin.password()).andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));

        client.get("/api/audit-logs").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        client.get("/api/auth/me").andExpect(status().isOk());
        String sessionBefore = client.cookie(ApiClient.SESSION_COOKIE).getValue();

        client.post("/api/auth/change-password", change(admin.password(), NEW_PASSWORD))
                .andExpect(status().isNoContent());
        entityManager.flush();

        assertThat(client.cookie(ApiClient.SESSION_COOKIE).getValue()).isNotEqualTo(sessionBefore);
        client.get("/api/audit-logs").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT must_change_password FROM users WHERE id = ?", Boolean.class,
                admin.id())).isFalse();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, admin.id()))
                .startsWith("{bcrypt}$2a$12$");
    }

    @Test
    void onlyTheNewPasswordWorksAfterwards() throws Exception {
        TestUser seller = users.create("Seller");
        client.login(seller.username(), seller.password()).andExpect(status().isOk());
        client.post("/api/auth/change-password", change(seller.password(), NEW_PASSWORD))
                .andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();

        ApiClient other = ApiClient.create(context, "198.51.100.21");
        other.login(seller.username(), seller.password()).andExpect(status().isUnauthorized());
        other.login(seller.username(), NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void wrongCurrentPasswordIsAFieldError() throws Exception {
        TestUser seller = users.create("Seller");
        client.login(seller.username(), seller.password()).andExpect(status().isOk());

        client.post("/api/auth/change-password", change("Not-the-password-1", NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("currentPassword"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
    }

    @Test
    void newPasswordMustBeLongEnoughAndNotCommon() throws Exception {
        TestUser seller = users.create("Seller");
        client.login(seller.username(), seller.password()).andExpect(status().isOk());

        client.post("/api/auth/change-password", change(seller.password(), "Short-pw-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_SHORT"));
        client.post("/api/auth/change-password", change(seller.password(), "Q1W2E3R4T5Y6"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("TOO_COMMON"));
    }

    @Test
    void anonymousCannotChangeAPassword() throws Exception {
        client.post("/api/auth/change-password", change("whatever-whatever", NEW_PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    private static String change(String current, String next) {
        return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}";
    }
}
