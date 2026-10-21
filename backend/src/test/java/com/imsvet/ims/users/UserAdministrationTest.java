package com.imsvet.ims.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import jakarta.persistence.EntityManager;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * API test (A) of user administration (FR-SEC-002, api.md §4.2, ADR-045), with the audit of every change
 * (BR-AUDIT-001) and optimistic locking (ADR-027).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class UserAdministrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String INITIAL = "Amber-meadow-quartz-11";

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

    private TestUser admin;
    private ApiClient client;

    @BeforeEach
    void logIn() throws Exception {
        clock.reset();
        admin = new TestUsers(jdbc, encoder).create("Administrator");
        client = ApiClient.create(context, "198.51.100.80");
        client.login(admin.username(), admin.password()).andExpect(status().isOk());
    }

    @Test
    void createdUserMustChangeTheInitialPasswordAndCreationIsAudited() throws Exception {
        JsonNode created = json(create("new.seller", "seller@example.org", "Seller", INITIAL)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/users/")))
                .andExpect(jsonPath("$.username").value("new.seller"))
                .andExpect(jsonPath("$.role.name").value("Seller"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.version").value(0)));
        assertThat(created.toString()).doesNotContain(INITIAL).doesNotContainIgnoringCase("hash");

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT user_id, new_values FROM audit_log WHERE action = 'USER_CREATE' AND entity_id = ?",
                created.get("id").asLong());
        assertThat(audit.get("user_id")).isEqualTo(admin.id());
        assertThat((String) audit.get("new_values")).contains("new.seller").doesNotContain(INITIAL);

        ApiClient newUser = ApiClient.create(context, "198.51.100.81");
        newUser.login("new.seller", INITIAL).andExpect(jsonPath("$.mustChangePassword").value(true));
    }

    @Test
    void duplicatesAreRejectedCaseAndAccentInsensitively() throws Exception {
        create("dup.user", "dup@example.org", "Seller", INITIAL).andExpect(status().isCreated());

        create("DUP.USER", null, "Seller", INITIAL).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.context.field").value("username"));
        create("other.user", "DUP@example.org", "Seller", INITIAL).andExpect(status().isConflict())
                .andExpect(jsonPath("$.context.field").value("email"));
    }

    @Test
    void invalidCreationsAreFieldErrors() throws Exception {
        create("weak.user", null, "Seller", "short-pass").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("initialPassword"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_SHORT"));
        client.post("/api/users", "{\"username\":\"x\",\"fullName\":\"X\",\"roleId\":999,\"initialPassword\":\""
                + INITIAL + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("roleId"));
        client.post("/api/users", "{\"username\":\"\",\"fullName\":\"X\",\"email\":\"not-an-email\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void searchFiltersAndPages() throws Exception {
        create("search.alpha", null, "Seller", INITIAL);
        create("search.beta", null, "Pharmacist", INITIAL);
        create("search_100%", null, "Seller", INITIAL);

        client.get("/api/users?q=search.&size=10").andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].username").value("search.alpha"));
        client.get("/api/users?q=search&roleId=" + roleId("Pharmacist")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].username").value("search.beta"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        client.perform(MockMvcRequestBuilders.get("/api/users").param("q", "_100%"), true).andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1));
        client.get("/api/users?active=false&q=search").andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void updateChangesNameEmailAndRoleAuditsTheDifferenceAndChecksTheVersion() throws Exception {
        long id = json(create("upd.user", null, "Seller", INITIAL)).get("id").asLong();
        entityManager.flush();

        put(id, "Updated Name", "upd@example.org", "Pharmacist", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("upd.user"))
                .andExpect(jsonPath("$.fullName").value("Updated Name"))
                .andExpect(jsonPath("$.role.name").value("Pharmacist"))
                .andExpect(jsonPath("$.version").value(1));
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT old_values->>'$.role' AS old_role, new_values->>'$.role' AS new_role,
                       JSON_CONTAINS_PATH(new_values, 'one', '$.username') AS has_username
                FROM audit_log WHERE action = 'USER_UPDATE' AND entity_id = ?""", id);
        assertThat(audit).containsEntry("old_role", "Seller").containsEntry("new_role", "Pharmacist")
                .containsEntry("has_username", 0L);

        put(id, "Stale Edit", null, "Seller", 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void deactivatedUserCannotLogInAndActivationRestoresAccess() throws Exception {
        TestUser seller = new TestUsers(jdbc, encoder).create("Seller");

        client.post("/api/users/" + seller.id() + "/deactivate", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        ApiClient sellerClient = ApiClient.create(context, "198.51.100.82");
        sellerClient.login(seller.username(), seller.password()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_ACCOUNT_DISABLED"));

        client.post("/api/users/" + seller.id() + "/activate", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        sellerClient.login(seller.username(), seller.password()).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE entity_id = ? ORDER BY id", String.class,
                seller.id())).containsExactly("USER_DEACTIVATE", "USER_UPDATE");
    }

    @Test
    void resetIssuesATemporaryPasswordThatMustBeChanged() throws Exception {
        TestUser seller = new TestUsers(jdbc, encoder).create("Seller");

        String temporary = json(client.post("/api/users/" + seller.id() + "/reset-password", "")
                .andExpect(status().isOk())).get("temporaryPassword").asString();
        entityManager.flush();
        entityManager.clear();

        assertThat(temporary).matches("[a-z2-9]{4}(-[a-z2-9]{4}){3}");
        ApiClient sellerClient = ApiClient.create(context, "198.51.100.83");
        sellerClient.login(seller.username(), seller.password()).andExpect(status().isUnauthorized());
        sellerClient.login(seller.username(), temporary).andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));
        String audit = jdbc.queryForObject(
                "SELECT CONCAT(action, ' ', new_values) FROM audit_log WHERE entity_id = ?", String.class, seller.id());
        assertThat(audit).startsWith("PASSWORD_RESET").doesNotContain(temporary);
    }

    @Test
    void unknownUserIsNotFound() throws Exception {
        client.get("/api/users/999999").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private ResultActions create(String username, String email, String role, String password) throws Exception {
        String emailJson = email == null ? "null" : "\"" + email + "\"";
        return client.post("/api/users", "{\"username\":\"" + username + "\",\"fullName\":\"Test " + username
                + "\",\"email\":" + emailJson + ",\"roleId\":" + roleId(role) + ",\"initialPassword\":\"" + password
                + "\"}");
    }

    private ResultActions put(long id, String fullName, String email, String role, long version) throws Exception {
        String emailJson = email == null ? "null" : "\"" + email + "\"";
        return client.perform(MockMvcRequestBuilders.put("/api/users/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"" + fullName + "\",\"email\":" + emailJson + ",\"roleId\":" + roleId(role)
                        + ",\"version\":" + version + "}"), true);
    }

    private short roleId(String role) {
        return jdbc.queryForObject("SELECT id FROM roles WHERE name = ?", Short.class, role);
    }

    private static JsonNode json(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
