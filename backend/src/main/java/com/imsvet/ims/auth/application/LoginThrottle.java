package com.imsvet.ims.auth.application;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.web.ProblemDetailsHandler;
import com.imsvet.ims.settings.application.SettingsProvider;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * ADR-038 / BR-SEC-005: failed logins are counted per username + client IP in memory (single instance).
 * After {@code login_max_failures} failures within {@code login_window_minutes} the pair is refused for
 * {@code login_lockout_minutes}, without checking the password. Accounts are never locked in the database.
 */
@Component
public class LoginThrottle {

    static final String MAX_FAILURES = "login_max_failures";
    static final String WINDOW_MINUTES = "login_window_minutes";
    static final String LOCKOUT_MINUTES = "login_lockout_minutes";

    private static final int PRUNE_THRESHOLD = 10_000;

    private final SettingsProvider settings;
    private final Clock clock;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public LoginThrottle(SettingsProvider settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    /** @throws BusinessException {@code RATE_LIMITED} with {@code retryAfterSeconds} while locked out */
    public void checkAllowed(String username, String clientIp) {
        Instant now = clock.instant();
        Attempts a = attempts.get(key(username, clientIp));
        Instant lockedUntil = a == null ? null : a.lockedUntil();
        if (lockedUntil != null && now.isBefore(lockedUntil)) {
            long seconds = Math.max(1, Duration.between(now, lockedUntil).toSeconds());
            throw new BusinessException(ErrorCode.RATE_LIMITED, "Too many failed logins. Try again later.",
                    Map.of(ProblemDetailsHandler.RETRY_AFTER_SECONDS, seconds));
        }
    }

    public void recordFailure(String username, String clientIp) {
        Instant now = clock.instant();
        Duration window = Duration.ofMinutes(settings.intValue(WINDOW_MINUTES));
        Duration lockout = Duration.ofMinutes(settings.intValue(LOCKOUT_MINUTES));
        int maxFailures = settings.intValue(MAX_FAILURES);
        attempts.compute(key(username, clientIp), (k, a) -> {
            Attempts next = a == null ? new Attempts() : a;
            next.failures().removeIf(at -> !at.isAfter(now.minus(window)));
            next.failures().addLast(now);
            if (next.failures().size() >= maxFailures) {
                next.failures().clear();
                return new Attempts(next.failures(), now.plus(lockout));
            }
            return new Attempts(next.failures(), null);
        });
        if (attempts.size() > PRUNE_THRESHOLD) {
            attempts.values().removeIf(a -> a.isStale(now, window));
        }
    }

    public void reset(String username, String clientIp) {
        attempts.remove(key(username, clientIp));
    }

    /** Same identity as the username column: case- and accent-insensitive (ADR-041). */
    private static String key(String username, String clientIp) {
        String folded = Normalizer.normalize(username.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        return folded + '|' + clientIp;
    }

    private record Attempts(Deque<Instant> failures, Instant lockedUntil) {

        Attempts() {
            this(new ArrayDeque<>(), null);
        }

        boolean isStale(Instant now, Duration window) {
            boolean locked = lockedUntil != null && now.isBefore(lockedUntil);
            return !locked && failures.stream().noneMatch(at -> at.isAfter(now.minus(window)));
        }
    }
}
