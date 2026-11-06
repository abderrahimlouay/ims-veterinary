package com.imsvet.ims.backup.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One backup run (ADR-144, BR-BKP-001): written when it ends. A success names its file in the backup folder; a failure
 * keeps its reason, never a secret. A success also keeps the result of its copy to the copy folder (ADR-167), when
 * one is configured. Only {@code removedAt} (retention, ADR-145) and {@code lastDownloadedAt} (ADR-146) change
 * afterwards.
 */
@Entity
@Table(name = "backup_log")
public class BackupRecord {

    public static final String SCHEDULED = "scheduled";
    public static final String MANUAL = "manual";
    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";
    public static final String COPIED = "copied";

    /** ADR-167: the copy of a successful backup; {@link #NONE} when no copy folder is configured. */
    public record Copy(String status, String failureReason) {

        public static final Copy NONE = new Copy(null, null);

        public static Copy copied() {
            return new Copy(COPIED, null);
        }

        public static Copy failed(String reason) {
            return new Copy(FAILED, reason);
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trigger_type", nullable = false, updatable = false, length = 20)
    private String triggerType;

    @Column(name = "started_by", updatable = false)
    private Long startedBy;

    @Column(nullable = false, updatable = false, length = 20)
    private String status;

    @Column(name = "file_name", updatable = false, length = 120)
    private String fileName;

    @Column(name = "size_bytes", updatable = false)
    private Long sizeBytes;

    @Column(name = "failure_reason", updatable = false, length = 500)
    private String failureReason;

    @Column(name = "copy_status", updatable = false, length = 20)
    private String copyStatus;

    @Column(name = "copy_failure_reason", updatable = false, length = 500)
    private String copyFailureReason;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false, updatable = false)
    private Instant finishedAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "last_downloaded_at")
    private Instant lastDownloadedAt;

    protected BackupRecord() {
    }

    private BackupRecord(Long startedBy, String status, String fileName, Long sizeBytes, String failureReason,
            Copy copy, Instant startedAt, Instant finishedAt) {
        this.triggerType = startedBy == null ? SCHEDULED : MANUAL;
        this.startedBy = startedBy;
        this.status = status;
        this.fileName = fileName;
        this.sizeBytes = sizeBytes;
        this.failureReason = failureReason;
        this.copyStatus = copy.status();
        this.copyFailureReason = copy.failureReason();
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    /** {@code startedBy} {@code null}: the daily job. */
    public static BackupRecord success(Long startedBy, String fileName, long sizeBytes, Copy copy, Instant startedAt,
            Instant finishedAt) {
        return new BackupRecord(startedBy, SUCCESS, fileName, sizeBytes, null, copy, startedAt, finishedAt);
    }

    public static BackupRecord failure(Long startedBy, String reason, Instant startedAt, Instant finishedAt) {
        return new BackupRecord(startedBy, FAILED, null, null, reason, Copy.NONE, startedAt, finishedAt);
    }

    public void removed(Instant at) {
        this.removedAt = at;
    }

    public void downloaded(Instant at) {
        this.lastDownloadedAt = at;
    }

    public boolean succeeded() {
        return SUCCESS.equals(status);
    }

    public boolean available() {
        return succeeded() && removedAt == null;
    }

    public Long id() {
        return id;
    }

    public String triggerType() {
        return triggerType;
    }

    public Long startedBy() {
        return startedBy;
    }

    public String status() {
        return status;
    }

    public String fileName() {
        return fileName;
    }

    public Long sizeBytes() {
        return sizeBytes;
    }

    public String failureReason() {
        return failureReason;
    }

    public String copyStatus() {
        return copyStatus;
    }

    public String copyFailureReason() {
        return copyFailureReason;
    }

    public boolean copyFailed() {
        return FAILED.equals(copyStatus);
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public Instant removedAt() {
        return removedAt;
    }

    public Instant lastDownloadedAt() {
        return lastDownloadedAt;
    }
}
