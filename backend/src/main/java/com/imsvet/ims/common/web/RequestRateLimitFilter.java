package com.imsvet.ims.common.web;

import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.security.SecurityLog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The coarse per-address limit on {@code /api} (ADR-157, ADR-166; security.md §11), formerly the reverse proxy's: a
 * token bucket per client address, {@code ims.security.rate-limit.per-second} tokens a second up to
 * {@code burst}; an empty bucket answers 429 {@code RATE_LIMITED}. Login keeps its own throttling (ADR-038). The client
 * address is the connection's: no proxy sits in front of the application, so no forwarded header is trusted.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@ConditionalOnBooleanProperty(name = "ims.security.rate-limit.enabled")
public class RequestRateLimitFilter extends OncePerRequestFilter {

    /** Buckets idle this long are forgotten, so the map stays small. */
    private static final long FORGET_AFTER_MILLIS = 60_000;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;
    private final ProblemResponseWriter problems;
    private final double perSecond;
    private final double burst;
    private volatile long lastSweep;

    public RequestRateLimitFilter(Clock clock, ProblemResponseWriter problems,
            @Value("${ims.security.rate-limit.per-second:20}") double perSecond,
            @Value("${ims.security.rate-limit.burst:40}") double burst) {
        this.clock = clock;
        this.problems = problems;
        this.perSecond = perSecond;
        this.burst = burst;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = clock.millis();
        sweep(now);
        Bucket bucket = buckets.computeIfAbsent(request.getRemoteAddr(), a -> new Bucket(burst, now));
        if (!bucket.take(now, perSecond, burst)) {
            SecurityLog.LOG.atInfo().addKeyValue("event", "rate_limited")
                    .addKeyValue("path", request.getRequestURI()).log("Security event rate_limited");
            problems.write(request, response, ErrorCode.RATE_LIMITED, "Too many requests from this device; retry in a moment.");
            return;
        }
        chain.doFilter(request, response);
    }

    private void sweep(long now) {
        if (now - lastSweep > FORGET_AFTER_MILLIS) {
            lastSweep = now;
            buckets.values().removeIf(b -> b.idleSince(now) > FORGET_AFTER_MILLIS);
        }
    }

    private static final class Bucket {

        private double tokens;
        private long updated;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.updated = now;
        }

        synchronized boolean take(long now, double perSecond, double burst) {
            tokens = Math.min(burst, tokens + (now - updated) * perSecond / 1000.0);
            updated = Math.max(updated, now);
            if (tokens < 1) {
                return false;
            }
            tokens -= 1;
            return true;
        }

        synchronized long idleSince(long now) {
            return now - updated;
        }
    }
}
