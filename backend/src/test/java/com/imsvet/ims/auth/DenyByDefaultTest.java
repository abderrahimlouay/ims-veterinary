package com.imsvet.ims.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * BR-SEC-001: every mapped endpoint, enumerated from the application, refuses an anonymous request with
 * {@code AUTH_REQUIRED} — except {@code POST /api/auth/login}. Requests carry a valid CSRF token so that only
 * authentication is under test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DenyByDefaultTest {

    private static final Set<String> PUBLIC = Set.of("POST /api/auth/login");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    void everyEndpointExceptLoginRequiresAuthentication() throws Exception {
        ApiClient anonymous = ApiClient.create(context, "198.51.100.30");
        List<String> endpoints = new ArrayList<>();
        mappings.getHandlerMethods().keySet().forEach(info -> {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                for (RequestMethod method : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    endpoints.add(method + " " + pattern);
                }
            }
        });
        assertThat(endpoints).contains("POST /api/auth/login", "GET /api/auth/me", "GET /api/audit-logs");

        for (String endpoint : endpoints) {
            String[] parts = endpoint.split(" ");
            String uri = parts[1].replaceAll("\\{[^}]+}", "1");
            MockHttpServletResponse response = anonymous.perform(MockMvcRequestBuilders
                    .request(HttpMethod.valueOf(parts[0]), uri)
                    .contentType(MediaType.APPLICATION_JSON).content("{}"), true).andReturn().getResponse();
            if (PUBLIC.contains(endpoint)) {
                assertThat(response.getStatus()).as(endpoint).isNotEqualTo(401);
            } else {
                assertThat(response.getStatus()).as(endpoint).isEqualTo(401);
                assertThat(response.getContentAsString()).as(endpoint).contains("\"code\":\"AUTH_REQUIRED\"");
            }
        }
    }

    @Test
    void onlyHealthIsPublicAmongActuatorEndpoints() throws Exception {
        ApiClient anonymous = ApiClient.create(context, "198.51.100.31");

        assertThat(anonymous.get("/actuator/health").andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(anonymous.get("/actuator/info").andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(anonymous.get("/actuator/env").andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
