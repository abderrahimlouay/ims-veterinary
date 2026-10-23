package com.imsvet.ims.common.db;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Startup report when one account is configured for both roles (ADR-087). */
class SharedDatabaseAccountAnalyzer extends AbstractFailureAnalyzer<SharedDatabaseAccountException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, SharedDatabaseAccountException cause) {
        return new FailureAnalysis("The database account '" + cause.account() + "' is used for both migrations and "
                + "the application; the application account must not be able to change the schema or the ledgers.",
                DatabaseAccountsGuidance.ACTION, cause);
    }
}
