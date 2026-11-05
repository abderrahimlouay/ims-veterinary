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
 * keeps its reason, never a secret. Only {@code removedAt} (retention, ADR-145) and {@code lastDownloadedAt} (ADR-146)
 * change afterwards.
 */
@Entity
@Table(name = "backup_log")
public class BackupRecord {

    public static final String SCHEDULED = "scheduled";
    public static final String MANUAL = "manual";
    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";

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
            Instant startedAt, Instant finishedAt) {
        this.triggerType = startedBy == null ? SCHEDULED : MANUAL;
        this.startedBy = startedBy;
        this.status = status;
        this.fileName = fileName;
        this.sizeBytes = sizeBytes;
        this.failureReason = failureReason;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    /** {@code startedBy} {@code null}: the daily job. */
    public static BackupRecord success(Long startedBy, String fileName, long sizeBytes, Instant startedAt,
            Instant finishedAt) {
        return new BackupRecord(startedBy, SUCCESS, fileName, sizeBytes, null, startedAt, finishedAt);
    }

    public static BackupRecord failure(Long startedBy, String reason, Instant startedAt, Instant finishedAt) {
        return new BackupRecord(startedBy, FAILED, null, null, reason, startedAt, finishedAt);
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
