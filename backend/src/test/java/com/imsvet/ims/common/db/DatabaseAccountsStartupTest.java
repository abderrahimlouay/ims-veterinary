package com.imsvet.ims.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.ImsApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * ADR-087 at a real start: with the migration account missing, or with one account for both roles, the application
 * stops before connecting and prints what to do, instead of an authentication error from the database.
 */
@ExtendWith(OutputCaptureExtension.class)
class DatabaseAccountsStartupTest {

    @Test
    void aMissingMigrationPasswordStopsWithTheGuidance(CapturedOutput output) {
        start("--IMS_DB_URL=jdbc:mysql://127.0.0.1:9/ims", "--IMS_DB_USERNAME=ims_app",
                "--IMS_DB_PASSWORD=unused", "--IMS_DB_MIGRATION_USERNAME=ims_migrator");

        assertThat(output.getAll()).contains("IMS_DB_MIGRATION_PASSWORD is not set")
                .contains("Database accounts (ADR-087)");
    }

    @Test
    void oneAccountForBothRolesStopsWithTheGuidance(CapturedOutput output) {
        start("--IMS_DB_URL=jdbc:mysql://127.0.0.1:9/ims", "--IMS_DB_USERNAME=ims",
                "--IMS_DB_PASSWORD=unused", "--IMS_DB_MIGRATION_USERNAME=ims", "--IMS_DB_MIGRATION_PASSWORD=unused");

        assertThat(output.getAll()).contains("'ims' is used for both migrations and the application")
                .contains("Database accounts (ADR-087)");
    }

    /** Port 9 (discard) is never MySQL: the check must stop the start before any connection is attempted. */
    private static void start(String... args) {
        SpringApplication application = new SpringApplication(ImsApplication.class);
        try {
            application.run(args).close();
        } catch (RuntimeException expected) {
            // The start must fail; the report printed by the failure analyzers is asserted by the caller.
        }
    }
}
