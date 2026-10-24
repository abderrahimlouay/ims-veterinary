package com.imsvet.ims.demo;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The application clock under the {@code demo} profile: the system UTC clock, except while the seeder pins it to a
 * moment of the demo history, so documents, movements and audit entries carry realistic dates.
 */
public final class DemoClock extends Clock {

    private volatile Instant pinned;

    void pin(Instant instant) {
        pinned = instant;
    }

    void release() {
        pinned = null;
    }

    @Override
    public Instant instant() {
        Instant at = pinned;
        return at != null ? at : Instant.now();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant(), zone);
    }
}
