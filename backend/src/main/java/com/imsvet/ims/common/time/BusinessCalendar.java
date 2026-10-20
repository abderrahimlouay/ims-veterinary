package com.imsvet.ims.common.time;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * "Today" in the business time zone, from the injected {@link Clock} (ADR-010). Use this, never
 * {@code LocalDate.now()}, for expiry checks and document years.
 */
@Component
public class BusinessCalendar {

    private final Clock clock;
    private final BusinessProperties business;

    public BusinessCalendar(Clock clock, BusinessProperties business) {
        this.clock = clock;
        this.business = business;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(business.timeZone()));
    }
}
