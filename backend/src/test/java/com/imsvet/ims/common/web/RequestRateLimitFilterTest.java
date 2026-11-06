package com.imsvet.ims.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** ADR-166 (formerly the proxy's, ADR-157): 20 requests a second per address, bursts of 40, then 429. */
class RequestRateLimitFilterTest {

    private Instant now = Instant.parse("2026-11-06T09:00:00Z");
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };
    private final RequestRateLimitFilter filter = new RequestRateLimitFilter(clock,
            new ProblemResponseWriter(JsonMapper.builder().build()), 20, 40);

    @Test
    void aBurstOfFortyPassesTheFortyFirstIsRefusedThenTokensComeBackWithTime() throws Exception {
        for (int i = 0; i < 40; i++) {
            assertThat(call("/api/products", "192.168.1.20").getStatus()).as("request %d", i + 1).isEqualTo(200);
        }
        MockHttpServletResponse refused = call("/api/products", "192.168.1.20");
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");

        now = now.plus(Duration.ofMillis(100));
        assertThat(call("/api/products", "192.168.1.20").getStatus()).as("two tokens back after 100 ms").isEqualTo(200);
        assertThat(call("/api/products", "192.168.1.20").getStatus()).isEqualTo(200);
        assertThat(call("/api/products", "192.168.1.20").getStatus()).isEqualTo(429);
    }

    @Test
    void eachAddressHasItsOwnBucketAndTheScreensAreNotLimited() throws Exception {
        for (int i = 0; i < 41; i++) {
            call("/api/products", "192.168.1.20");
        }
        assertThat(call("/api/products", "192.168.1.21").getStatus()).isEqualTo(200);
        assertThat(call("/assets/app.js", "192.168.1.20").getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse call(String path, String address) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(address);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
