package com.imsvet.ims.auth;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
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
 * BR-SEC-005 / ADR-038: throttling per username + client IP with limits from app_settings (5 failures in
 * 15 min → refused for 15 min). The throttle is shared in memory, so every test uses its own client IP.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class LoginThrottleTest {

    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);
    private static final String WRONG = "Not-the-password-1";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    private TestUser user;
    private String ip;
    private ApiClient client;

    @BeforeEach
    void setUp() {
        clock.reset();
        user = new TestUsers(jdbc, encoder).create("Seller");
        ip = "203.0.113." + NEXT_IP.getAndIncrement();
        client = ApiClient.create(context, ip);
    }

    @Test
    void fifthFailureLocksThePairEvenForTheRightPassword() throws Exception {
        fail(5);

        client.login(user.username(), user.password())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string("Retry-After", "900"));
    }

    @Test
    void otherIpAndOtherUsernameAreNotAffected() throws Exception {
        fail(5);

        ApiClient.create(context, ip + "0").login(user.username(), user.password()).andExpect(status().isOk());
        TestUser other = new TestUsers(jdbc, encoder).create("Seller");
        client.login(other.username(), other.password()).andExpect(status().isOk());
    }

    @Test
    void caseAndAccentVariantsOfTheUsernameShareTheCounter() throws Exception {
        for (int i = 0; i < 5; i++) {
            client.login(i % 2 == 0 ? user.username().toUpperCase() : user.username(), WRONG)
                    .andExpect(status().isUnauthorized());
        }

        client.login(user.username(), user.password()).andExpect(status().isTooManyRequests());
    }

    @Test
    void lockoutEndsAfterTheLockoutPeriod() throws Exception {
        fail(5);

        clock.advance(Duration.ofMinutes(15).minusSeconds(1));
        client.login(user.username(), user.password()).andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofSeconds(1));
        client.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void failuresOutsideTheWindowDoNotCount() throws Exception {
        fail(4);
        clock.advance(Duration.ofMinutes(15));
        fail(1);

        client.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void successfulLoginResetsTheCounter() throws Exception {
        fail(4);
        client.login(user.username(), user.password()).andExpect(status().isOk());
        fail(4);

        client.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void limitsComeFromTheSettings() throws Exception {
        jdbc.update("UPDATE app_settings SET setting_value = '2' WHERE setting_key = 'login_max_failures'");
        jdbc.update("UPDATE app_settings SET setting_value = '1' WHERE setting_key = 'login_lockout_minutes'");
        fail(2);

        client.login(user.username(), user.password()).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
    }

    private void fail(int times) throws Exception {
        for (int i = 0; i < times; i++) {
            client.login(user.username(), WRONG).andExpect(status().isUnauthorized());
        }
    }
}
