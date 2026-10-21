package com.imsvet.ims.audit;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * API test (A) of {@code GET /api/audit-logs} (api.md §4.14): envelope and parameter validation.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AuditLogEndpointTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    private ApiClient auditor;

    @BeforeEach
    void logIn() throws Exception {
        clock.reset();
        TestUser accountant = new TestUsers(jdbc, encoder).create("Accountant");
        auditor = ApiClient.create(context, "198.51.100.60");
        auditor.login(accountant.username(), accountant.password()).andExpect(status().isOk());
    }

    @Test
    void returnsThePagingEnvelope() throws Exception {
        auditor.get("/api/audit-logs?entity=nothing-here&page=0&size=5").andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(5))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    /** ADR-046: an auditor without {@code user.read} still sees who acted. */
    @Test
    void entriesNameTheActingUser() throws Exception {
        TestUser admin = new TestUsers(jdbc, encoder).create("Administrator");
        ApiClient adminClient = ApiClient.create(context, "198.51.100.61");
        adminClient.login(admin.username(), admin.password()).andExpect(status().isOk());
        adminClient.perform(MockMvcRequestBuilders.put("/api/settings/default_vat_rate")
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"19\"}"), true)
                .andExpect(status().isOk());

        auditor.get("/api/audit-logs?entity=app_settings&action=SETTING_CHANGE").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].userId").value(admin.id()))
                .andExpect(jsonPath("$.content[0].username").value(admin.username()))
                .andExpect(jsonPath("$.content[0].userFullName").value("Test User"));
    }

    @Test
    void rejectsInvalidParametersAsValidationFailures() throws Exception {
        auditor.get("/api/audit-logs?action=NOT_AN_ACTION").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        auditor.get("/api/audit-logs?size=101").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        auditor.get("/api/audit-logs?from=2026-10-21&to=2026-10-20").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
