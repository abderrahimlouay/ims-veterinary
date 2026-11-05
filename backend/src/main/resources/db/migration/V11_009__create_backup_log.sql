-- One row per backup run, scheduled or started with Run now (ADR-144, ADR-145, BR-BKP-001, SD-155): written when the
-- run ends, success or failure. The file is named, never located by path (the folder comes from the environment).
-- After retention deletes the file the row stays, removed_at set. last_downloaded_at feeds the off-site reminder
-- (ADR-146); each download is also audited BACKUP_DOWNLOAD.
CREATE TABLE backup_log (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    trigger_type       VARCHAR(20)  NOT NULL,
    started_by         BIGINT       NULL,
    status             VARCHAR(20)  NOT NULL,
    file_name          VARCHAR(120) NULL,
    size_bytes         BIGINT       NULL,
    failure_reason     VARCHAR(500) NULL,
    started_at         DATETIME(6)  NOT NULL,
    finished_at        DATETIME(6)  NOT NULL,
    removed_at         DATETIME(6)  NULL,
    last_downloaded_at DATETIME(6)  NULL,
    CONSTRAINT pk_backup_log PRIMARY KEY (id),
    CONSTRAINT uq_backup_log_file_name UNIQUE (file_name),
    CONSTRAINT fk_backup_log_started_by FOREIGN KEY (started_by) REFERENCES users (id),
    CONSTRAINT ck_backup_log_trigger CHECK (trigger_type IN ('scheduled', 'manual')
        AND (trigger_type = 'manual') = (started_by IS NOT NULL)),
    CONSTRAINT ck_backup_log_status CHECK (status IN ('success', 'failed')),
    CONSTRAINT ck_backup_log_outcome CHECK (
        (status = 'success' AND file_name IS NOT NULL AND size_bytes IS NOT NULL AND failure_reason IS NULL)
        OR (status = 'failed' AND file_name IS NULL AND size_bytes IS NULL AND failure_reason IS NOT NULL
            AND removed_at IS NULL AND last_downloaded_at IS NULL)),
    CONSTRAINT ck_backup_log_times CHECK (finished_at >= started_at),
    INDEX idx_backup_log_started (started_at),
    INDEX idx_backup_log_status (status, started_at)
);
