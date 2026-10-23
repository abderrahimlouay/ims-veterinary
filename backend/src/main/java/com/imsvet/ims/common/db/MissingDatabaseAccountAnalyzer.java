package com.imsvet.ims.common.db;

import java.util.Set;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.util.PlaceholderResolutionException;

/** Startup report when a database account variable is missing, e.g. a single-account setup (ADR-087). */
class MissingDatabaseAccountAnalyzer extends AbstractFailureAnalyzer<PlaceholderResolutionException> {

    private static final Set<String> ACCOUNT_VARIABLES = Set.of("IMS_DB_URL", "IMS_DB_USERNAME", "IMS_DB_PASSWORD",
            "IMS_DB_MIGRATION_USERNAME", "IMS_DB_MIGRATION_PASSWORD");

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, PlaceholderResolutionException cause) {
        String variable = cause.getPlaceholder();
        if (!ACCOUNT_VARIABLES.contains(variable)) {
            return null;
        }
        return new FailureAnalysis("The environment variable " + variable + " is not set. The application connects "
                + "with an application account and migrates with a separate migration account.",
                DatabaseAccountsGuidance.ACTION, cause);
    }
}
