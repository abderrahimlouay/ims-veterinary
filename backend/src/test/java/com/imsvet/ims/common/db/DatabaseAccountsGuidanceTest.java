package com.imsvet.ims.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.PlaceholderResolutionException;

/**
 * ADR-087: starting with a missing account variable, or with one account for both roles, ends with a message that
 * says what to do (create both accounts, set the four variables, read the README section), not a bare stack trace.
 */
class DatabaseAccountsGuidanceTest {

    @Test
    void aMissingMigrationAccountVariableSaysToCreateBothAccounts() {
        FailureAnalysis analysis = new MissingDatabaseAccountAnalyzer().analyze(missing("IMS_DB_MIGRATION_USERNAME"));

        assertThat(analysis.getDescription()).contains("IMS_DB_MIGRATION_USERNAME").contains("not set");
        assertGuidance(analysis.getAction());
    }

    @Test
    void otherMissingPlaceholdersAreLeftToSpringBoot() {
        assertThat(new MissingDatabaseAccountAnalyzer().analyze(missing("SOME_OTHER_VARIABLE"))).isNull();
    }

    @Test
    void oneAccountForBothRolesSaysToCreateBothAccounts() {
        SharedDatabaseAccountException shared = new SharedDatabaseAccountException("ims");

        FailureAnalysis analysis = new SharedDatabaseAccountAnalyzer().analyze(shared);

        assertThat(shared.getMessage()).contains("'ims'").contains("README.md");
        assertThat(analysis.getDescription()).contains("'ims'").contains("both");
        assertGuidance(analysis.getAction());
    }

    private static void assertGuidance(String action) {
        assertThat(action).contains("migration account").contains("application account")
                .contains("IMS_DB_MIGRATION_USERNAME").contains("IMS_DB_MIGRATION_PASSWORD")
                .contains("IMS_DB_USERNAME").contains("IMS_DB_PASSWORD")
                .contains("README.md").contains("Database accounts (ADR-087)");
    }

    /** The exception Spring raises at startup when a required placeholder has no value. */
    private static PlaceholderResolutionException missing(String variable) {
        return catchThrowableOfType(PlaceholderResolutionException.class,
                () -> new StandardEnvironment().resolveRequiredPlaceholders("${" + variable + "}"));
    }
}
