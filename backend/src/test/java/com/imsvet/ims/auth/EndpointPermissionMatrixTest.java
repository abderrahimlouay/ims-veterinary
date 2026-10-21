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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
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

    record Endpoint(HttpMethod method, String uri, String permission) {
    }

    static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint(HttpMethod.GET, "/api/audit-logs", "audit.read"));

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
            boolean granted = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM role_permissions rp JOIN roles r ON r.id = rp.role_id
                    JOIN permissions p ON p.id = rp.permission_id WHERE r.name = ? AND p.code = ?""",
                    Integer.class, role, endpoint.permission()) > 0;
            MockHttpServletResponse response = client.perform(
                    MockMvcRequestBuilders.request(endpoint.method(), endpoint.uri()), true).andReturn().getResponse();

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
