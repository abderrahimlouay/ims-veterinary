package com.imsvet.ims.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.MySqlContainerConfiguration;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.web.TraceIdFilter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-031 over real HTTP: health is public without details, every other actuator endpoint needs a session
 * (BR-SEC-001), logs are JSON with the request trace id and without secrets.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class HealthAndLoggingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private RestTestClient client;

    @Test
    void healthIsUpWithoutDetailsAndCarriesTraceId() {
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectHeader().exists(TraceIdFilter.HEADER)
                .expectBody().json("{\"status\":\"UP\"}")
                .jsonPath("$.components").doesNotExist()
                .jsonPath("$.details").doesNotExist();
    }

    @Test
    void everyOtherActuatorEndpointRequiresAuthentication() {
        for (String endpoint : new String[] {"info", "env", "beans", "configprops", "heapdump", "loggers", "mappings"}) {
            client.get().uri("/actuator/" + endpoint).exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody().jsonPath("$.code").isEqualTo("AUTH_REQUIRED");
        }
    }

    /**
     * The ECS format nests fields ({@code {"log":{"level":"INFO"}}}), so lines are parsed, not searched.
     * A failed login logs a security event inside the request, which is where the MDC trace id applies; the
     * submitted password and other secrets must never appear in any line (ADR-031).
     */
    @Test
    void applicationLogsAreStructuredJsonWithTraceIdAndNoSecrets(CapturedOutput output) {
        String traceId = "logtest-" + UUID.randomUUID();
        String password = "canary-password-" + UUID.randomUUID();
        String bearerToken = "canary-bearer-" + UUID.randomUUID();
        String queryToken = "canary-query-" + UUID.randomUUID();
        String csrf = client.get().uri("/api/auth/me").exchange().returnResult()
                .getResponseCookies().getFirst("XSRF-TOKEN").getValue();

        client.post().uri("/api/auth/login?token=" + queryToken)
                .contentType(MediaType.APPLICATION_JSON)
                .cookie("XSRF-TOKEN", csrf)
                .header("X-XSRF-TOKEN", csrf)
                .header(TraceIdFilter.HEADER, traceId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .body("{\"username\":\"nobody-logtest\",\"password\":\"" + password + "\"}")
                .exchange()
                .expectStatus().isUnauthorized();

        List<String> lines = output.getOut().lines().filter(l -> l.startsWith("{")).toList();
        List<JsonNode> entries = lines.stream().map(JSON::readTree).toList();

        assertThat(entries).isNotEmpty().allSatisfy(entry -> {
            assertThat(entry.at("/@timestamp").isString()).as("@timestamp in %s", entry).isTrue();
            assertThat(entry.at("/log/level").isString()).as("log.level in %s", entry).isTrue();
            assertThat(entry.at("/message").isString()).as("message in %s", entry).isTrue();
        });
        assertThat(entries)
                .filteredOn(entry -> traceId.equals(entry.path(TraceIdFilter.MDC_KEY).asString(null)))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.at("/log/level").asString()).isEqualTo("INFO");
                    assertThat(entry.at("/log/logger").asString()).isEqualTo("ims.security");
                    assertThat(entry.path("event").asString()).isEqualTo("login_failed");
                    assertThat(entry.path("username").asString()).isEqualTo("nobody-logtest");
                });
        assertThat(lines).noneSatisfy(line -> assertThat(line)
                .containsAnyOf(MySqlContainerConfiguration.MYSQL_PASSWORD, password, bearerToken, queryToken));
    }
}
