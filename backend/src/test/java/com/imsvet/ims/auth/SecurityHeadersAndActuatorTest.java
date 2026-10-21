package com.imsvet.ims.auth;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
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
 * security.md §11 headers on API responses; ADR-031 actuator exposure once authenticated.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SecurityHeadersAndActuatorTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    @BeforeEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void apiResponsesCarryTheSecurityHeaders() throws Exception {
        ApiClient.create(context, "198.51.100.50").get("/api/auth/me")
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void authenticatedUsersSeeInfoButNoOtherActuatorEndpoint() throws Exception {
        TestUser admin = new TestUsers(jdbc, encoder).create("Administrator");
        ApiClient client = ApiClient.create(context, "198.51.100.51");
        client.login(admin.username(), admin.password()).andExpect(status().isOk());

        client.get("/actuator/info").andExpect(status().isOk());
        for (String endpoint : new String[] {"env", "beans", "configprops", "heapdump", "loggers", "mappings"}) {
            client.get("/actuator/" + endpoint).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }
}
