package com.imsvet.ims.common.db;

/** What to do when the two database accounts of ADR-087 are missing or the same: one text for logs and analyzers. */
final class DatabaseAccountsGuidance {

    static final String ACTION = "Create two MySQL accounts: a migration account (Flyway only, every right on the "
            + "schema with GRANT OPTION) and a different application account (no rights; Flyway grants them). Set "
            + "IMS_DB_MIGRATION_USERNAME and IMS_DB_MIGRATION_PASSWORD for the migration account, and IMS_DB_USERNAME "
            + "and IMS_DB_PASSWORD for the application account. See README.md, section \"Database accounts (ADR-087)\".";

    private DatabaseAccountsGuidance() {
    }
}
