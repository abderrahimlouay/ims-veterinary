package com.imsvet.ims.common.db;

/** ADR-087: Flyway and the application were given the same database account. */
public class SharedDatabaseAccountException extends IllegalStateException {

    private final String account;

    public SharedDatabaseAccountException(String account) {
        super("The database account '" + account + "' is configured for both Flyway and the application. "
                + DatabaseAccountsGuidance.ACTION);
        this.account = account;
    }

    public String account() {
        return account;
    }
}
