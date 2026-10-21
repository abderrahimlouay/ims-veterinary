package com.imsvet.ims.users;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * ADR-042: no change may leave the system without an active user holding both {@code user.manage} and
 * {@code role.manage}; nobody deactivates themselves. The test administrator is the only one in the database.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AdministratorLockoutTest {

    private static final String WITHOUT_ROLE_MANAGE =
            "{\"permissionCodes\":[\"user.read\",\"user.manage\",\"audit.read\"]}";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    private TestUsers users;
    private TestUser admin;
    private ApiClient client;

    @BeforeEach
    void logIn() throws Exception {
        clock.reset();
        users = new TestUsers(jdbc, encoder);
        admin = users.create("Administrator");
        client = ApiClient.create(context, "198.51.100.90");
        client.login(admin.username(), admin.password()).andExpect(status().isOk());
    }

    @Test
    void nobodyDeactivatesThemselves() throws Exception {
        users.create("Administrator");

        refused(client.post("/api/users/" + admin.id() + "/deactivate", ""));
    }

    @Test
    void anotherAdministratorCanBeDeactivatedButNotTheLastOne() throws Exception {
        TestUser second = users.create("Administrator");
        client.post("/api/users/" + second.id() + "/deactivate", "").andExpect(status().isOk());

        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'user.manage'
                WHERE r.name = 'Pharmacist'""");
        TestUser pharmacist = users.create("Pharmacist");
        ApiClient pharmacistClient = ApiClient.create(context, "198.51.100.91");
        pharmacistClient.login(pharmacist.username(), pharmacist.password()).andExpect(status().isOk());

        refused(pharmacistClient.post("/api/users/" + admin.id() + "/deactivate", ""));
    }

    @Test
    void theOnlyAdministratorCannotLoseTheAdministratorRole() throws Exception {
        refused(client.perform(MockMvcRequestBuilders.put("/api/users/" + admin.id())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Me\",\"roleId\":"
                        + roleId("Seller") + ",\"version\":0}"), true));
    }

    @Test
    void theAdministratorRoleCannotLoseTheAdministrationPermissions() throws Exception {
        refused(replace("Administrator", WITHOUT_ROLE_MANAGE));
    }

    /** Separate from the refusal: inside this test's transaction a refused change is undone only at its end. */
    @Test
    void theAdministratorRoleMayLoseThemWhenAnActiveUserOfAnotherRoleHoldsThem() throws Exception {
        replace("Pharmacist", "{\"permissionCodes\":[\"user.read\",\"user.manage\",\"role.manage\"]}")
                .andExpect(status().isOk());
        users.create("Pharmacist");

        replace("Administrator", WITHOUT_ROLE_MANAGE).andExpect(status().isOk());
    }

    private ResultActions replace(String role, String body) throws Exception {
        return client.perform(MockMvcRequestBuilders.put("/api/roles/" + roleId(role) + "/permissions")
                .contentType(MediaType.APPLICATION_JSON).content(body), true);
    }

    private static void refused(ResultActions actions) throws Exception {
        actions.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    private short roleId(String role) {
        return jdbc.queryForObject("SELECT id FROM roles WHERE name = ?", Short.class, role);
    }
}
