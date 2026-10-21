package com.imsvet.ims.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * BR-SEC-002: endpoint × role matrix. Each guarded endpoint is called by a user of each of the four roles;
 * the expected outcome comes from the seeded mapping (= security.md §4, ADR-037): allowed → not 403,
 * denied → 403 {@code ACCESS_DENIED}. Later slices add their endpoints to {@link #ENDPOINTS}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class EndpointPermissionMatrixTest {

    /** {@code permission} {@value #AUTHENTICATED}: any logged-in user; {@code body}: a valid request body. */
    record Endpoint(HttpMethod method, String uri, String permission, String body) {

        Endpoint(HttpMethod method, String uri, String permission) {
            this(method, uri, permission, null);
        }
    }

    static final String AUTHENTICATED = "authenticated";

    static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint(HttpMethod.GET, "/api/audit-logs", "audit.read"),
            new Endpoint(HttpMethod.GET, "/api/settings/public", AUTHENTICATED),
            new Endpoint(HttpMethod.GET, "/api/settings", "settings.manage"),
            new Endpoint(HttpMethod.GET, "/api/settings/default_vat_rate", "settings.manage"),
            new Endpoint(HttpMethod.PUT, "/api/settings/default_vat_rate", "settings.manage", "{\"value\":\"9.00\"}"),
            new Endpoint(HttpMethod.GET, "/api/users", "user.read"),
            new Endpoint(HttpMethod.GET, "/api/users/999999", "user.read"),
            new Endpoint(HttpMethod.POST, "/api/users", "user.manage",
                    "{\"username\":\"matrix.user\",\"fullName\":\"Matrix\",\"roleId\":3,\"initialPassword\":\""
                            + TestUsers.PASSWORD + "\"}"),
            new Endpoint(HttpMethod.PUT, "/api/users/999999", "user.manage",
                    "{\"fullName\":\"X\",\"roleId\":3,\"version\":0}"),
            new Endpoint(HttpMethod.POST, "/api/users/999999/deactivate", "user.manage"),
            new Endpoint(HttpMethod.POST, "/api/users/999999/activate", "user.manage"),
            new Endpoint(HttpMethod.POST, "/api/users/999999/reset-password", "user.manage"),
            new Endpoint(HttpMethod.GET, "/api/roles", "user.read"),
            new Endpoint(HttpMethod.GET, "/api/roles/1", "user.read"),
            new Endpoint(HttpMethod.GET, "/api/permissions", "user.read"),
            new Endpoint(HttpMethod.PUT, "/api/roles/999/permissions", "role.manage", "{\"permissionCodes\":[]}"),
            new Endpoint(HttpMethod.GET, "/api/animal-species", "product.read"),
            new Endpoint(HttpMethod.GET, "/api/categories", "product.read"),
            new Endpoint(HttpMethod.POST, "/api/manufacturers", "reference.manage", "{\"name\":\"Matrix Labs\"}"),
            new Endpoint(HttpMethod.PUT, "/api/dosage-forms/999999", "reference.manage", "{\"name\":\"X\"}"),
            new Endpoint(HttpMethod.DELETE, "/api/active-ingredients/999999", "reference.manage"));

    static final List<String> ROLES = List.of("Administrator", "Pharmacist", "Seller", "Accountant");

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

    static List<String> roles() {
        return ROLES;
    }

    @ParameterizedTest
    @MethodSource("roles")
    void eachRoleGetsExactlyTheEndpointsOfItsPermissions(String role) throws Exception {
        TestUser user = new TestUsers(jdbc, encoder).create(role);
        ApiClient client = ApiClient.create(context, "198.51.100.40");
        client.login(user.username(), user.password());

        for (Endpoint endpoint : ENDPOINTS) {
            boolean granted = AUTHENTICATED.equals(endpoint.permission()) || jdbc.queryForObject("""
                    SELECT COUNT(*) FROM role_permissions rp JOIN roles r ON r.id = rp.role_id
                    JOIN permissions p ON p.id = rp.permission_id WHERE r.name = ? AND p.code = ?""",
                    Integer.class, role, endpoint.permission()) > 0;
            MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(endpoint.method(), endpoint.uri());
            if (endpoint.body() != null) {
                request.contentType(MediaType.APPLICATION_JSON).content(endpoint.body());
            }
            MockHttpServletResponse response = client.perform(request, true).andReturn().getResponse();

            if (granted) {
                assertThat(response.getStatus()).as("%s %s as %s", endpoint.method(), endpoint.uri(), role)
                        .isNotIn(401, 403);
            } else {
                assertThat(response.getStatus()).as("%s %s as %s", endpoint.method(), endpoint.uri(), role)
                        .isEqualTo(403);
                assertThat(response.getContentAsString()).contains("\"code\":\"ACCESS_DENIED\"");
            }
        }
    }
}
