-- Backup schedule, retention and off-site reminder (ADR-145, ADR-146), editable and audited like every setting.
INSERT INTO app_settings (setting_key, setting_value, description, updated_at) VALUES
    ('backup_time', '02:00', 'Time of the daily backup, business time zone (ADR-145)', UTC_TIMESTAMP(6)),
    ('backup_retention_count', '30', 'Successful backups kept on the server; older files are deleted (ADR-145)',
     UTC_TIMESTAMP(6)),
    ('backup_download_reminder_days', '7', 'Days without a backup download before the backups page warns (ADR-146)',
     UTC_TIMESTAMP(6));
