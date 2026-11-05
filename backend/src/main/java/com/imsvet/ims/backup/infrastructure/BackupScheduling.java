package com.imsvet.ims.backup.infrastructure;

import com.imsvet.ims.backup.application.BackupJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Ticks the daily backup every 30 s (ADR-145, backend.md §12); the job decides from {@code backup_time} and the clock
 * whether to run. Off with {@code ims.jobs.enabled=false} (tests call the tick directly). Single instance assumed.
 */
@Configuration
@EnableScheduling
@ConditionalOnBooleanProperty(name = "ims.jobs.enabled", matchIfMissing = true)
class BackupScheduling {

    private final BackupJob job;

    BackupScheduling(BackupJob job) {
        this.job = job;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    void tick() {
        job.tick();
    }
}
