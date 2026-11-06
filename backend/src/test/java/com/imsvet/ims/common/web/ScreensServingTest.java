package com.imsvet.ims.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.TestcontainersConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-166 over real HTTP: the application serves the built screens (a stand-in build in the test resources), each
 * screen route answered with {@code index.html} under the screens' headers of {@code frontend/src/securityHeaders.json},
 * hashed assets cached, the screens read-only and public, the API unchanged behind it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class ScreensServingTest {

    @Autowired
    private RestTestClient client;

    @Test
    void aScreenRouteIsTheIndexUnderTheFrontendHeadersAndNeverCached() throws Exception {
        Map<String, String> expected = JsonMapper.builder().build().readValue(
                Files.readString(Path.of("../frontend/src/securityHeaders.json")),
                new TypeReference<Map<String, String>>() {
                });
        for (String path : new String[] {"/", "/products/42", "/reports/revenue"}) {
            var result = client.get().uri(path).exchange().expectStatus().isOk()
                    .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE)
                    .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .expectBody(String.class).returnResult();
            assertThat(result.getResponseBody()).as(path).contains("test-index");
            expected.forEach((name, value) -> assertThat(result.getResponseHeaders().getFirst(name)).as(name).isEqualTo(value));
        }
    }

    @Test
    void hashedAssetsAreCachedForAYearAndAMissingOneIsNotTheIndex() {
        client.get().uri("/assets/app-test.js").exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "max-age=31536000, public, immutable")
                .expectBody(String.class).value(body -> assertThat(body).contains("test-asset"));
        client.get().uri("/assets/missing.js").exchange().expectStatus().isNotFound();
    }

    @Test
    void theScreensAreReadOnly() {
        client.post().uri("/products").exchange().expectStatus().is4xxClientError()
                .expectBody(String.class).value(body -> assertThat(body).doesNotContain("test-index"));
    }

    @Test
    void theApiIsUnchangedBehindTheScreens() {
        client.get().uri("/api/products").exchange().expectStatus().isUnauthorized()
                .expectHeader().valueEquals("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                .expectBody().jsonPath("$.code").isEqualTo("AUTH_REQUIRED");
        client.get().uri("/api/no-such-endpoint").exchange().expectStatus().isUnauthorized();
    }
}
