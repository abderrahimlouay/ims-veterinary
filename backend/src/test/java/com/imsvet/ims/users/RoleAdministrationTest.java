package com.imsvet.ims.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * API test (A) of roles and permissions (api.md §4.2, ADR-037): catalogue, mapping change, audit.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RoleAdministrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    private TestUser admin;
    private ApiClient client;

    @BeforeEach
    void logIn() throws Exception {
        clock.reset();
        admin = new TestUsers(jdbc, encoder).create("Administrator");
        client = ApiClient.create(context, "198.51.100.100");
        client.login(admin.username(), admin.password()).andExpect(status().isOk());
    }

    @Test
    void listsRolesWithTheirPermissionsAndTheCatalogue() throws Exception {
        client.get("/api/roles").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[?(@.name == 'Administrator')].permissions[*]", hasSize(54)));
        client.get("/api/permissions").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(54)))
                .andExpect(jsonPath("$[0].code").value("audit.read"));
        client.get("/api/roles/" + roleId("Seller")).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Seller"))
                .andExpect(jsonPath("$.permissions", hasSize(12)));
    }

    @Test
    void replacingPermissionsIsAuditedAndAppliesToTheRoleUsersAtOnce() throws Exception {
        TestUser seller = new TestUsers(jdbc, encoder).create("Seller");
        ApiClient sellerClient = ApiClient.create(context, "198.51.100.101");
        sellerClient.login(seller.username(), seller.password()).andExpect(status().isOk());
        sellerClient.get("/api/audit-logs").andExpect(status().isForbidden());

        replace("Seller", "{\"permissionCodes\":[\"sale.read\",\"audit.read\"]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", hasSize(2)));

        sellerClient.get("/api/audit-logs").andExpect(status().isOk());
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT user_id, JSON_LENGTH(old_values->'$.permissions') AS before_count,
                       new_values->'$.permissions' AS after_codes
                FROM audit_log WHERE action = 'ROLE_PERMISSION_CHANGE' AND entity_id = ?""", roleId("Seller"));
        assertThat(audit.get("user_id")).isEqualTo(admin.id());
        assertThat(audit.get("before_count")).isEqualTo(12L);
        assertThat((String) audit.get("after_codes")).isEqualTo("[\"audit.read\", \"sale.read\"]");
    }

    @Test
    void unknownCodesAndRolesAreRejected() throws Exception {
        replace("Seller", "{\"permissionCodes\":[\"sale.read\",\"no.such.code\"]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("permissionCodes"))
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN"));
        client.perform(MockMvcRequestBuilders.put("/api/roles/999/permissions")
                .contentType(MediaType.APPLICATION_JSON).content("{\"permissionCodes\":[]}"), true)
                .andExpect(status().isNotFound());
    }

    private ResultActions replace(String role, String body) throws Exception {
        return client.perform(MockMvcRequestBuilders.put("/api/roles/" + roleId(role) + "/permissions")
                .contentType(MediaType.APPLICATION_JSON).content(body), true);
    }

    private short roleId(String role) {
        return jdbc.queryForObject("SELECT id FROM roles WHERE name = ?", Short.class, role);
    }
}
