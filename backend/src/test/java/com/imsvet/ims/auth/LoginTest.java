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
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * Login, session and logout over HTTP (FR-SEC-001, BR-SEC-001, BR-SEC-004, ADR-036).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class LoginTest {

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
        client = ApiClient.create(context, "198.51.100.10");
    }

    @Test
    void loginReturnsTheCurrentUserAndOpensASession() throws Exception {
        TestUser seller = users.create("Seller");

        String body = client.login(seller.username(), seller.password())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(seller.id()))
                .andExpect(jsonPath("$.user.username").value(seller.username()))
                .andExpect(jsonPath("$.user.role").value("Seller"))
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.hasItem("sale.create")))
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("user.manage"))))
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(seller.password()).doesNotContainIgnoringCase("hash");

        client.get("/api/auth/me").andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value(seller.username()));
        assertThat(jdbc.queryForObject("SELECT last_login_at FROM users WHERE id = ?", LocalDateTime.class,
                seller.id()).toInstant(ZoneOffset.UTC)).isEqualTo(TestClock.START);
    }

    @Test
    void sessionAndCsrfCookiesHaveTheRequiredFlags() throws Exception {
        TestUser seller = users.create("Seller");
        client.login(seller.username(), seller.password()).andExpect(status().isOk());

        Cookie session = client.cookie(ApiClient.SESSION_COOKIE);
        assertThat(session.isHttpOnly()).isTrue();
        assertThat(session.getSecure()).isTrue();
        assertThat(session.getAttribute("SameSite")).isEqualToIgnoringCase("Strict");
        Cookie csrf = client.cookie(ApiClient.CSRF_COOKIE);
        assertThat(csrf.isHttpOnly()).as("the SPA must read the CSRF token").isFalse();
        assertThat(csrf.getSecure()).isTrue();
        assertThat(csrf.getAttribute("SameSite")).isEqualToIgnoringCase("Strict");
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameGenericError() throws Exception {
        TestUser seller = users.create("Seller");

        String wrongPassword = client.login(seller.username(), "Not-the-password-1")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();
        String unknownUser = client.login("nobody-" + seller.username(), "Not-the-password-1")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();

        assertThat(detailOf(wrongPassword)).isEqualTo(detailOf(unknownUser));
    }

    @Test
    void usernameIsMatchedCaseAndAccentInsensitively() throws Exception {
        TestUser seller = users.create("Seller");

        client.login(seller.username().toUpperCase(), seller.password()).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(seller.id()));
    }

    @Test
    void inactiveUserIsRefusedOnlyAfterTheRightPassword() throws Exception {
        TestUser disabled = users.create("Seller", false, false);

        client.login(disabled.username(), "Not-the-password-1").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
        client.login(disabled.username(), disabled.password()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_ACCOUNT_DISABLED"));
    }

    @Test
    void stateChangeWithoutTheCsrfHeaderIsRefused() throws Exception {
        TestUser seller = users.create("Seller");

        client.postWithoutCsrfHeader("/api/auth/login",
                        "{\"username\":\"" + seller.username() + "\",\"password\":\"" + seller.password() + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void everyLoginGetsANewSessionId() throws Exception {
        TestUser seller = users.create("Seller");
        client.login(seller.username(), seller.password()).andExpect(status().isOk());
        String first = client.cookie(ApiClient.SESSION_COOKIE).getValue();

        client.login(seller.username(), seller.password()).andExpect(status().isOk());

        assertThat(client.cookie(ApiClient.SESSION_COOKIE).getValue()).isNotEqualTo(first);
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        TestUser seller = users.create("Seller");
        client.login(seller.username(), seller.password()).andExpect(status().isOk());

        client.post("/api/auth/logout", "").andExpect(status().isNoContent());

        client.get("/api/auth/me").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void deactivationTakesEffectOnTheNextRequest() throws Exception {
        TestUser seller = users.create("Seller");
        client.login(seller.username(), seller.password()).andExpect(status().isOk());

        jdbc.update("UPDATE users SET is_active = FALSE WHERE id = ?", seller.id());
        startNewRequestScope();

        client.get("/api/auth/me").andExpect(status().isUnauthorized());
    }

    @Test
    void roleChangeTakesEffectOnTheNextRequest() throws Exception {
        TestUser seller = users.create("Seller");
        client.login(seller.username(), seller.password()).andExpect(status().isOk());
        client.get("/api/audit-logs").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        jdbc.update("UPDATE users SET role_id = (SELECT id FROM roles WHERE name = 'Accountant') WHERE id = ?",
                seller.id());
        startNewRequestScope();

        client.get("/api/audit-logs").andExpect(status().isOk());
    }

    @Test
    void sessionEndsAfterTheAbsoluteTimeout() throws Exception {
        TestUser seller = users.create("Seller");
        client.login(seller.username(), seller.password()).andExpect(status().isOk());

        clock.advance(Duration.ofHours(10).minusSeconds(1));
        client.get("/api/auth/me").andExpect(status().isOk());
        clock.set(Instant.parse("2026-10-20T18:00:01Z"));
        client.get("/api/auth/me").andExpect(status().isUnauthorized());
    }

    /**
     * In production each request loads the user in its own transaction; inside this test's transaction all
     * requests share one persistence context, which must forget entities changed behind its back by SQL.
     */
    private void startNewRequestScope() {
        entityManager.flush();
        entityManager.clear();
    }

    private static String detailOf(String problemJson) {
        return problemJson.replaceAll(".*\"detail\":\"([^\"]*)\".*", "$1");
    }

}
