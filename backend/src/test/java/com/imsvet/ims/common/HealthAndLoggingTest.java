package com.imsvet.ims.common;

import static org.assertj.core.api.Assertions.assertThat;

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
 * ADR-031: only Actuator health and info are exposed, health shows no details, logs are JSON with the
 * request trace id and without secrets.
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
    void infoIsExposed() {
        client.get().uri("/actuator/info").exchange().expectStatus().isOk();
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() {
        for (String endpoint : new String[] {"env", "beans", "configprops", "heapdump", "loggers", "mappings"}) {
            client.get().uri("/actuator/" + endpoint).exchange()
                    .expectStatus().isNotFound()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
        }
    }

    /**
     * The ECS format nests fields ({@code {"log":{"level":"INFO"}}}), so lines are parsed, not searched.
     * {@code /api/test-problems/stale} logs at INFO inside the request, which is where the MDC trace id applies.
     */
    @Test
    void applicationLogsAreStructuredJsonWithTraceIdAndNoSecrets(CapturedOutput output) {
        String traceId = "logtest-" + UUID.randomUUID();
        String bearerToken = "canary-bearer-" + UUID.randomUUID();
        String queryToken = "canary-query-" + UUID.randomUUID();
        client.get().uri("/api/test-problems/stale?token=" + queryToken)
                .header(TraceIdFilter.HEADER, traceId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .exchange()
                .expectStatus().isEqualTo(409);

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
                    assertThat(entry.at("/message").asString()).startsWith("Concurrent modification");
                });
        assertThat(lines).noneSatisfy(line -> assertThat(line)
                .containsAnyOf(TestcontainersConfiguration.MYSQL_PASSWORD, bearerToken, queryToken));
    }
}
