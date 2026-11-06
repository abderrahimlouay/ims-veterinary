package com.imsvet.ims.backup.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.backup.domain.BackupRecord;
import com.imsvet.ims.backup.infrastructure.BackupFolder;
import com.imsvet.ims.backup.infrastructure.BackupProperties;
import com.imsvet.ims.backup.infrastructure.BackupRecordRepository;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.settings.application.SettingsProvider;
import com.imsvet.ims.settings.domain.SettingDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The backups of the administration (ADR-144…146, BR-BKP-001/002, api.md §4.14): the log, Run now (audited
 * {@code BACKUP_RUN}), the download of a kept backup by its record id (audited {@code BACKUP_DOWNLOAD}), and the state
 * the backups page warns about.
 */
@Service
public class Backups {

    /** {@code available}: the file is still kept and can be downloaded. */
    public record BackupView(long id, String trigger, Long startedBy, String status, String fileName, Long sizeBytes,
            String failureReason, String copyStatus, String copyFailureReason, Instant startedAt, Instant finishedAt,
            Instant removedAt, Instant lastDownloadedAt, boolean available) {
    }

    /**
     * {@code warnings}: {@code NOT_CONFIGURED}, {@code LAST_FAILED}, {@code NO_RECENT_BACKUP} (no success within a day),
     * {@code DOWNLOAD_OVERDUE} (no download for {@code backup_download_reminder_days}); with a copy folder (ADR-167)
     * {@code COPY_FAILED} (the last successful backup was not copied) and {@code NO_RECENT_COPY} (no copy within a day)
     * instead of {@code DOWNLOAD_OVERDUE}, the copy being the off-site copy.
     */
    public record BackupStatus(boolean configured, BackupView lastRun, Instant lastSuccessAt, Instant lastDownloadAt,
            int downloadReminderDays, boolean copyConfigured, Instant lastCopyAt, List<String> warnings) {
    }

    /** The bytes of a kept backup and the name to download it under. */
    public record BackupFile(String fileName, long sizeBytes, InputStream content) {
    }

    private static final Duration ONE_DAY = Duration.ofDays(1);

    private final BackupRunner runner;
    private final BackupRecordRepository records;
    private final BackupFolder folder;
    private final BackupProperties properties;
    private final SettingsProvider settings;
    private final AuditRecorder audit;
    private final ActorProvider actors;
    private final Clock clock;

    public Backups(BackupRunner runner, BackupRecordRepository records, BackupFolder folder,
            BackupProperties properties, SettingsProvider settings, AuditRecorder audit, ActorProvider actors,
            Clock clock) {
        this.runner = runner;
        this.records = records;
        this.folder = folder;
        this.properties = properties;
        this.settings = settings;
        this.audit = audit;
        this.actors = actors;
        this.clock = clock;
    }

    /** Newest first. */
    @PreAuthorize("hasAuthority('backup.manage')")
    @Transactional(readOnly = true)
    public PageResponse<BackupView> list(int page, int size) {
        return PageResponse.of(records.findAllByOrderByStartedAtDescIdDesc(PageRequests.of(page, size,
                Sort.unsorted())), Backups::view);
    }

    /** Runs a backup now and waits for it; a failed dump is answered as a failed record, not an error. */
    @PreAuthorize("hasAuthority('backup.manage')")
    @Transactional
    public BackupView runNow() {
        BackupRecord record = runner.run(actors.current().userId());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", record.status());
        values.put("fileName", record.fileName());
        values.put("failureReason", record.failureReason());
        audit.record(AuditAction.BACKUP_RUN, "backup_log", record.id(), null, values);
        return view(record);
    }

    /** {@code NOT_FOUND} for an unknown, failed or removed backup, or a file missing from the folder. */
    @PreAuthorize("hasAuthority('backup.manage')")
    @Transactional
    public BackupFile download(long id) {
        BackupRecord record = records.findById(id).filter(BackupRecord::available)
                .filter(r -> properties.configured() && folder.exists(r.fileName()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Backup not found.", Map.of("id", id)));
        InputStream content;
        try {
            content = folder.open(record.fileName());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Backup not found.", Map.of("id", id));
        }
        record.downloaded(clock.instant());
        records.saveAndFlush(record);
        audit.record(AuditAction.BACKUP_DOWNLOAD, "backup_log", record.id(), null,
                Map.of("fileName", record.fileName(), "sizeBytes", record.sizeBytes()));
        return new BackupFile(record.fileName(), record.sizeBytes(), content);
    }

    @PreAuthorize("hasAuthority('backup.manage')")
    @Transactional(readOnly = true)
    public BackupStatus status() {
        Instant now = clock.instant();
        int reminderDays = settings.intValue(SettingDefinition.BACKUP_DOWNLOAD_REMINDER_DAYS.key());
        BackupRecord last = records.findFirstByOrderByStartedAtDescIdDesc().orElse(null);
        BackupRecord lastSuccessful = records.findFirstByStatusOrderByFinishedAtDescIdDesc(BackupRecord.SUCCESS)
                .orElse(null);
        Instant lastSuccess = lastSuccessful == null ? null : lastSuccessful.finishedAt();
        boolean copying = properties.copyConfigured();
        Instant lastCopy = copying ? records.lastCopy().orElse(null) : null;
        Instant lastDownload = records.lastDownload().orElse(null);
        List<String> warnings = new ArrayList<>();
        if (!properties.configured()) {
            warnings.add("NOT_CONFIGURED");
        }
        if (last != null && !last.succeeded()) {
            warnings.add("LAST_FAILED");
        }
        if (lastSuccess == null || lastSuccess.isBefore(now.minus(ONE_DAY))) {
            warnings.add("NO_RECENT_BACKUP");
        }
        if (copying) {
            if (lastSuccessful != null && lastSuccessful.copyFailed()) {
                warnings.add("COPY_FAILED");
            }
            if (lastCopy == null || lastCopy.isBefore(now.minus(ONE_DAY))) {
                warnings.add("NO_RECENT_COPY");
            }
        } else if (lastDownload == null || lastDownload.isBefore(now.minus(Duration.ofDays(reminderDays)))) {
            warnings.add("DOWNLOAD_OVERDUE");
        }
        return new BackupStatus(properties.configured(), last == null ? null : view(last), lastSuccess, lastDownload,
                reminderDays, copying, lastCopy, warnings);
    }

    private static BackupView view(BackupRecord r) {
        return new BackupView(r.id(), r.triggerType(), r.startedBy(), r.status(), r.fileName(), r.sizeBytes(),
                r.failureReason(), r.copyStatus(), r.copyFailureReason(), r.startedAt(), r.finishedAt(), r.removedAt(), r.lastDownloadedAt(), r.available());
    }
}
