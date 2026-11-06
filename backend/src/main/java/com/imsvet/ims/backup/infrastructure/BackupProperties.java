package com.imsvet.ims.backup.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where and how backups are taken (ADR-144), from the environment only: {@code IMS_BACKUP_DIR} (a folder no web server
 * serves), {@code IMS_BACKUP_MYSQLDUMP} (the {@code mysqldump} program, {@code mysqldump} on the path by default) and
 * the read-only backup account {@code IMS_BACKUP_DB_USERNAME} / {@code IMS_BACKUP_DB_PASSWORD}. Without a folder or an
 * account, backups are "not configured": each run fails with that reason and the backups page warns. ADR-167:
 * {@code IMS_BACKUP_COPY_DIR}, optional, a second folder (Google Drive for desktop) each successful backup is copied to.
 */
@ConfigurationProperties(prefix = "ims.backup")
public record BackupProperties(String directory, String mysqldump, String username, String password,
        String copyDirectory) {

    public boolean configured() {
        return present(directory) && present(username) && password != null;
    }

    /** ADR-167: a copy folder is set, so every successful backup is also copied there. */
    public boolean copyConfigured() {
        return present(copyDirectory);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public String toString() {
        return "BackupProperties[directory=" + directory + ", mysqldump=" + mysqldump + ", username=" + username
                + ", copyDirectory=" + copyDirectory + "]";
    }
}
