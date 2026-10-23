package com.imsvet.ims.common.db;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.imsvet.ims.MySqlContainerConfiguration;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.MigrationAccount;
import java.sql.Connection;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * ADR-087 on the real database: when Flyway's account is also configured as the application account, the grants
 * callback stops with the guidance instead of granting an account its own rights.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ApplicationAccountGrantsGuardTest {

    @Autowired
    private MigrationAccount migration;

    @Test
    void theSameAccountForBothRolesIsRefusedWithGuidance() throws Exception {
        ApplicationAccountGrants grants = new ApplicationAccountGrants(MySqlContainerConfiguration.MIGRATION_USER, "%",
                "flyway_schema_history");
        try (Connection connection = migration.jdbc().getDataSource().getConnection()) {
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);

            assertThatThrownBy(() -> grants.handle(Event.AFTER_MIGRATE, context))
                    .isInstanceOf(SharedDatabaseAccountException.class)
                    .hasMessageContaining("'" + MySqlContainerConfiguration.MIGRATION_USER + "'")
                    .hasMessageContaining("Database accounts (ADR-087)");
        }
    }
}
