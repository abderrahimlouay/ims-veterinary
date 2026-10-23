package com.imsvet.ims.inventory.application;

import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.common.time.BusinessSettings;
import com.imsvet.ims.inventory.infrastructure.IntegrityCheckRunRepository;
import com.imsvet.ims.settings.application.SettingsProvider;
import com.imsvet.ims.settings.domain.SettingDefinition;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The nightly integrity check (ADR-086): once per business day, at the first tick at or after
 * {@code integrity_check_time} in the business time zone (so a run missed while the application was down happens
 * on its next start that day). Time and setting come from the injected clock and the settings at each tick.
 */
@Component
public class IntegrityCheckJob {

    private final IntegrityCheckRunner runner;
    private final IntegrityCheckRunRepository runs;
    private final SettingsProvider settings;
    private final BusinessCalendar calendar;
    private final BusinessSettings business;
    private final Clock clock;

    public IntegrityCheckJob(IntegrityCheckRunner runner, IntegrityCheckRunRepository runs, SettingsProvider settings,
            BusinessCalendar calendar, BusinessSettings business, Clock clock) {
        this.runner = runner;
        this.runs = runs;
        this.settings = settings;
        this.calendar = calendar;
        this.business = business;
        this.clock = clock;
    }

    @Transactional
    public void tick() {
        LocalDate today = calendar.today();
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(business.timeZone()));
        LocalTime at = LocalTime.parse(settings.value(SettingDefinition.INTEGRITY_CHECK_TIME));
        if (now.toLocalTime().isBefore(at)) {
            return;
        }
        if (runs.nightlyRunSince(today.atStartOfDay(business.timeZone()).toInstant())) {
            return;
        }
        runner.run(null);
    }
}
