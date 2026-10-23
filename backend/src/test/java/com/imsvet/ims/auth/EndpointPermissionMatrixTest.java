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
            new Endpoint(HttpMethod.DELETE, "/api/active-ingredients/999999", "reference.manage"),
            new Endpoint(HttpMethod.GET, "/api/products", "product.read"),
            new Endpoint(HttpMethod.GET, "/api/products/999999", "product.read"),
            new Endpoint(HttpMethod.POST, "/api/products", "product.create",
                    "{\"sku\":\"MATRIX-1\",\"commercialName\":\"Matrix\",\"baseUnit\":\"ml\",\"divisible\":true}"),
            new Endpoint(HttpMethod.PUT, "/api/products/999999", "product.update",
                    "{\"sku\":\"MATRIX-2\",\"commercialName\":\"Matrix\",\"baseUnit\":\"ml\",\"divisible\":true,"
                            + "\"vatRate\":\"9\",\"version\":0}"),
            new Endpoint(HttpMethod.POST, "/api/products/999999/archive", "product.archive"),
            new Endpoint(HttpMethod.POST, "/api/products/999999/restore", "product.archive"),
            new Endpoint(HttpMethod.PUT, "/api/products/999999/ingredients", "product.update", "[]"),
            new Endpoint(HttpMethod.GET, "/api/products/999999/substitutes", "product.read"),
            new Endpoint(HttpMethod.PUT, "/api/products/999999/target-species", "product.update", "{\"speciesIds\":[]}"),
            new Endpoint(HttpMethod.PUT, "/api/products/999999/withdrawal-periods", "product.update", "[]"),
            new Endpoint(HttpMethod.GET, "/api/products/999999/units", "product.read"),
            new Endpoint(HttpMethod.POST, "/api/products/999999/units", "product.update",
                    "{\"name\":\"Box\",\"factorToBase\":\"10\",\"sellable\":true}"),
            new Endpoint(HttpMethod.PUT, "/api/products/999999/units/999999", "product.update",
                    "{\"name\":\"Box\",\"factorToBase\":\"10\",\"sellable\":true,\"version\":0}"),
            new Endpoint(HttpMethod.GET, "/api/products/999999/barcodes", "product.read"),
            new Endpoint(HttpMethod.POST, "/api/products/999999/barcodes", "product.update",
                    "{\"code\":\"X1\",\"codeType\":\"QR\"}"),
            new Endpoint(HttpMethod.DELETE, "/api/products/999999/barcodes/999999", "product.update"),
            new Endpoint(HttpMethod.GET, "/api/products/by-barcode?code=X1", "product.read"),
            new Endpoint(HttpMethod.GET, "/api/lots", "lot.read"),
            new Endpoint(HttpMethod.GET, "/api/lots/999999", "lot.read"),
            new Endpoint(HttpMethod.GET, "/api/products/999999/lots", "lot.read"),
            new Endpoint(HttpMethod.GET, "/api/lots/fefo?productId=999999&quantity=1", "lot.read"),
            new Endpoint(HttpMethod.GET, "/api/lots/expiry-alerts", "lot.read"),
            new Endpoint(HttpMethod.POST, "/api/lots/999999/block", "lot.block", "{\"reason\":\"x\"}"),
            new Endpoint(HttpMethod.POST, "/api/lots/999999/unblock", "lot.unblock", "{\"reason\":\"x\"}"),
            new Endpoint(HttpMethod.POST, "/api/lots/999999/recall", "lot.recall", "{\"reason\":\"x\"}"),
            new Endpoint(HttpMethod.GET, "/api/lots/999999/recall-report", "lot.recall"),
            new Endpoint(HttpMethod.GET, "/api/inventory/movements", "stock.read"),
            new Endpoint(HttpMethod.GET, "/api/inventory/stock", "stock.read"),
            new Endpoint(HttpMethod.POST, "/api/inventory/adjustments", "stock.adjust",
                    "{\"lotId\":999999,\"type\":\"adjustment_breakage\",\"quantity\":\"1\",\"reason\":\"x\"}"),
            new Endpoint(HttpMethod.POST, "/api/inventory/opening-stock", "stock.adjust",
                    "{\"productId\":999999,\"lotNumber\":\"L\",\"expiryDate\":\"2027-01-31\",\"quantity\":\"1\",\"unitCost\":\"0\"}"));

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
