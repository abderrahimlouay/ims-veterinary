package com.imsvet.ims.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.web.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * ADR-031: only Actuator health and info are exposed, health shows no details, logs are JSON.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class HealthAndLoggingTest {

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

    @Test
    void applicationLogsAreStructuredJson(CapturedOutput output) {
        assertThat(output.getOut().lines().filter(l -> l.startsWith("{")))
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).contains("\"log.level\"").endsWith("}"));
    }
}
