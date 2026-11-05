package com.imsvet.ims.support;

/** The read-only backup account of the test database (ADR-144), for the backup tests. */
public record BackupAccount(String username, String password) {
}
