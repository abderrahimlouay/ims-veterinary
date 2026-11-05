package com.imsvet.ims.backup.application;

import com.imsvet.ims.backup.infrastructure.BackupRecordRepository;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.common.time.BusinessSettings;
import com.imsvet.ims.settings.application.SettingsProvider;
import com.imsvet.ims.settings.domain.SettingDefinition;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The daily backup (ADR-145): once per business day, at the first tick at or after {@code backup_time} in the
 * business time zone, so a run missed while the application was down happens on its next start that day. A failed
 * run counts as that day's run; the backups page then warns and an administrator may use Run now.
 */
@Component
public class BackupJob {

    private final BackupRunner runner;
    private final BackupRecordRepository records;
    private final SettingsProvider settings;
    private final BusinessCalendar calendar;
    private final BusinessSettings business;
    private final Clock clock;

    public BackupJob(BackupRunner runner, BackupRecordRepository records, SettingsProvider settings,
            BusinessCalendar calendar, BusinessSettings business, Clock clock) {
        this.runner = runner;
        this.records = records;
        this.settings = settings;
        this.calendar = calendar;
        this.business = business;
        this.clock = clock;
    }

    @Transactional
    public void tick() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(business.timeZone()));
        if (now.toLocalTime().isBefore(LocalTime.parse(settings.value(SettingDefinition.BACKUP_TIME)))) {
            return;
        }
        if (records.scheduledRunSince(calendar.startOf(calendar.today()))) {
            return;
        }
        try {
            runner.run(null);
        } catch (BusinessException alreadyRunning) {
            // a Run now is in progress: the next tick checks again
        }
    }
}
