package com.imsvet.ims;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Deterministic, settable UTC clock for integration tests (testing.md §1.6). Shared by every test of a
 * context: tests that move it call {@link #reset()} first.
 */
public final class TestClock extends Clock {

    public static final Instant START = Instant.parse("2026-10-20T08:00:00Z");

    private volatile Instant now = START;

    public void reset() {
        now = START;
    }

    public void set(Instant instant) {
        now = instant;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(now, zone);
    }
}
