package com.imsvet.ims.backup.application;

import com.imsvet.ims.backup.domain.BackupRecord;
import com.imsvet.ims.backup.infrastructure.BackupFolder;
import com.imsvet.ims.backup.infrastructure.BackupFolder.Writing;
import com.imsvet.ims.backup.infrastructure.BackupProperties;
import com.imsvet.ims.backup.infrastructure.BackupRecordRepository;
import com.imsvet.ims.backup.infrastructure.DatabaseDumper;
import com.imsvet.ims.backup.infrastructure.DatabaseDumper.BackupFailure;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.settings.application.SettingsProvider;
import com.imsvet.ims.settings.domain.SettingDefinition;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Takes one backup (ADR-144, ADR-145, BR-BKP-001): the dump gzip-compressed into the backup folder under a new name,
 * then one {@code backup_log} row, success or failure; after a success, the files beyond the newest
 * {@code backup_retention_count} successful backups are deleted, with their copies, and their rows marked removed. With
 * a copy folder (ADR-167) each successful backup is copied there before its row is written, the copy's result in the
 * row; a failed copy never fails the backup. One backup at a time
 * (single instance): another request meanwhile is refused with {@code INVALID_STATE_TRANSITION}. The caller holds the
 * transaction and checks the permission.
 */
@Component
public class BackupRunner {

    private static final Logger LOG = LoggerFactory.getLogger(BackupRunner.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final String NOT_CONFIGURED = "Backups are not configured: set IMS_BACKUP_DIR, "
            + "IMS_BACKUP_DB_USERNAME and IMS_BACKUP_DB_PASSWORD (README, Backups).";

    private final AtomicBoolean running = new AtomicBoolean();
    private final DatabaseDumper dumper;
    private final BackupFolder folder;
    private final BackupProperties properties;
    private final BackupRecordRepository records;
    private final SettingsProvider settings;
    private final Clock clock;

    public BackupRunner(DatabaseDumper dumper, BackupFolder folder, BackupProperties properties,
            BackupRecordRepository records, SettingsProvider settings, Clock clock) {
        this.dumper = dumper;
        this.folder = folder;
        this.properties = properties;
        this.records = records;
        this.settings = settings;
        this.clock = clock;
    }

    /** {@code startedBy} {@code null}: the daily job. Never throws for a failed dump: the failure is the record. */
    public BackupRecord run(Long startedBy) {
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "A backup is already running.",
                    Map.of("state", "running"));
        }
        try {
            Instant started = clock.instant();
            if (!properties.configured()) {
                return failed(startedBy, NOT_CONFIGURED, started);
            }
            String name = "ims-backup-" + STAMP.format(started) + "-" + System.nanoTime() % 1_000_000 + ".sql.gz";
            long size;
            try (Writing file = folder.write(name)) {
                try (GZIPOutputStream gzip = new GZIPOutputStream(file.out())) {
                    dumper.dump(gzip);
                }
                size = file.keep();
            } catch (BackupFailure e) {
                return failed(startedBy, e.getMessage(), started);
            } catch (IOException | RuntimeException e) {
                return failed(startedBy, "The backup file could not be written: " + e.getClass().getSimpleName(),
                        started);
            }
            BackupRecord.Copy copy = copy(name);
            BackupRecord record = records.saveAndFlush(BackupRecord.success(startedBy, name, size, copy, started,
                    clock.instant()));
            LOG.info("Backup {} written ({} bytes)", name, size);
            prune();
            return record;
        } finally {
            running.set(false);
        }
    }

    private BackupRecord.Copy copy(String name) {
        if (!properties.copyConfigured()) {
            return BackupRecord.Copy.NONE;
        }
        try {
            folder.copy(name);
            return BackupRecord.Copy.copied();
        } catch (IOException | RuntimeException e) {
            String reason = "The copy to IMS_BACKUP_COPY_DIR failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            LOG.error("Backup {} not copied: {}", name, reason);
            return BackupRecord.Copy.failed(reason.length() > 500 ? reason.substring(0, 500) : reason);
        }
    }

    private BackupRecord failed(Long startedBy, String reason, Instant started) {
        LOG.error("Backup failed: {}", reason);
        return records.saveAndFlush(BackupRecord.failure(startedBy, reason, started, clock.instant()));
    }

    /** ADR-145: keeps the newest {@code backup_retention_count} successful backups. */
    private void prune() {
        int keep = settings.intValue(SettingDefinition.BACKUP_RETENTION_COUNT.key());
        List<BackupRecord> kept = records.kept();
        for (BackupRecord old : kept.subList(Math.min(keep, kept.size()), kept.size())) {
            try {
                folder.delete(old.fileName());
                if (properties.copyConfigured()) {
                    folder.deleteCopy(old.fileName());
                }
                old.removed(clock.instant());
                records.saveAndFlush(old);
            } catch (IOException e) {
                LOG.error("Backup {} could not be deleted: {}", old.fileName(), e.getClass().getSimpleName());
            }
        }
    }
}
