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
    /** Tokens are counted in thousandths, so whole numbers suffice (no binary floating point, ArchitectureTest). */
    private final long perSecond;
    private final long burstMilli;
    private volatile long lastSweep;

    public RequestRateLimitFilter(Clock clock, ProblemResponseWriter problems,
            @Value("${ims.security.rate-limit.per-second:20}") long perSecond,
            @Value("${ims.security.rate-limit.burst:40}") long burst) {
        this.clock = clock;
        this.problems = problems;
        this.perSecond = perSecond;
        this.burstMilli = burst * 1000;
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
        Bucket bucket = buckets.computeIfAbsent(request.getRemoteAddr(), a -> new Bucket(burstMilli, now));
        if (!bucket.take(now, perSecond, burstMilli)) {
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

        /** Thousandths of a token: {@code perSecond} of them come back each millisecond. */
        private long milliTokens;
        private long updated;

        Bucket(long milliTokens, long now) {
            this.milliTokens = milliTokens;
            this.updated = now;
        }

        synchronized boolean take(long now, long perSecond, long burstMilli) {
            milliTokens = Math.min(burstMilli, milliTokens + Math.max(0, now - updated) * perSecond);
            updated = Math.max(updated, now);
            if (milliTokens < 1000) {
                return false;
            }
            milliTokens -= 1000;
            return true;
        }

        synchronized long idleSince(long now) {
            return now - updated;
        }
    }
}
