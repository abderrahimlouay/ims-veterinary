-- ADR-167: after a successful backup the application copies the file to a second folder (Google Drive for desktop,
-- IMS_BACKUP_COPY_DIR). The copy's result is written with the row, in the same insert: NULL when no copy folder is
-- configured or the backup failed, 'copied', or 'failed' with its reason. The last copy feeds the backups page's
-- warnings (no successful copy within a day, the last copy failed).
ALTER TABLE backup_log
    ADD COLUMN copy_status VARCHAR(20) NULL AFTER failure_reason,
    ADD COLUMN copy_failure_reason VARCHAR(500) NULL AFTER copy_status,
    ADD CONSTRAINT ck_backup_log_copy CHECK (
        (copy_status IS NULL AND copy_failure_reason IS NULL)
        OR (copy_status = 'copied' AND status = 'success' AND copy_failure_reason IS NULL)
        OR (copy_status = 'failed' AND status = 'success' AND copy_failure_reason IS NOT NULL));
