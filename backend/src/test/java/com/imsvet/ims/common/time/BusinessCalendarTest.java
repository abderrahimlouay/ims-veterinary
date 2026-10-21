package com.imsvet.ims.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class BusinessCalendarTest {

    private record Fixed(Currency currency, Locale locale, ZoneId timeZone) implements BusinessSettings {
    }

    private static final BusinessSettings ALGIERS = new Fixed(
            Currency.getInstance("DZD"), Locale.forLanguageTag("fr-DZ"), ZoneId.of("Africa/Algiers"));

    @Test
    void todayFollowsTheBusinessTimeZoneNotUtc() {
        Clock lateEveningUtc = Clock.fixed(Instant.parse("2026-12-31T23:30:00Z"), ZoneOffset.UTC);

        assertThat(new BusinessCalendar(lateEveningUtc, ALGIERS).today()).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void todayIsUnchangedBeforeLocalMidnight() {
        Clock eveningUtc = Clock.fixed(Instant.parse("2026-12-31T22:59:59Z"), ZoneOffset.UTC);

        assertThat(new BusinessCalendar(eveningUtc, ALGIERS).today()).isEqualTo(LocalDate.of(2026, 12, 31));
    }
}
