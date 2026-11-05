package com.imsvet.ims.backup;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.MySqlContainerConfiguration;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.backup.application.BackupRunner;
import com.imsvet.ims.backup.domain.BackupRecord;
import com.imsvet.ims.support.MigrationAccount;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The restore rehearsal of ADR-144 item 4 (README, "Restoring a backup"): a backup taken by the application is
 * restored into an empty MySQL server by the written procedure (the administrator creates the empty schema and the
 * migration account, which imports the file), then Flyway validates the restored history against the application's
 * migrations, and the tables, views, triggers and rows match the original.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, BackupTestConfiguration.class})
class BackupRestoreRehearsalTest {

    private static final List<String> COUNTED = List.of("flyway_schema_history", "permissions", "roles",
            "role_permissions", "app_settings", "users");

    @Autowired
    private BackupRunner runner;

    @Autowired
    private Path backupDirectory;

    @Autowired
    private MySQLContainer mysql;

    @Autowired
    private MigrationAccount migration;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aBackupRestoresIntoAnEmptyServerAndPassesFlywayValidation() throws Exception {
        BackupRecord backup = runner.run(null);
        assertThat(backup.succeeded()).as(backup.failureReason()).isTrue();
        String schema = mysql.getDatabaseName();
        String rootPassword = "tc-restore-root-" + UUID.randomUUID();
        String migrationPassword = "tc-restore-migration-" + UUID.randomUUID();
        try (MySQLContainer restored = new MySQLContainer(MySqlContainerConfiguration.MYSQL_IMAGE)
                .withPassword(rootPassword).withCommand("--log-bin-trust-function-creators=1")) {
            restored.start();
            try (Connection root = DriverManager.getConnection(restored.getJdbcUrl(), "root", rootPassword);
                    Statement sql = root.createStatement()) {
                sql.execute("DROP DATABASE `" + schema + "`");
                sql.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                sql.execute("CREATE USER '" + MySqlContainerConfiguration.MIGRATION_USER + "'@'%' IDENTIFIED BY '"
                        + migrationPassword + "'");
                sql.execute("GRANT ALL PRIVILEGES ON `" + schema + "`.* TO '"
                        + MySqlContainerConfiguration.MIGRATION_USER + "'@'%' WITH GRANT OPTION");
                sql.execute("CREATE USER '" + MySqlContainerConfiguration.APP_USER + "'@'%' IDENTIFIED BY '"
                        + migrationPassword + "-app'");
            }
            restored.copyFileToContainer(MountableFile.forHostPath(backupDirectory.resolve(backup.fileName())),
                    "/tmp/backup.sql.gz");
            ExecResult imported = restored.execInContainer("sh", "-c", "gunzip -c /tmp/backup.sql.gz | mysql --user="
                    + MySqlContainerConfiguration.MIGRATION_USER + " --password='" + migrationPassword + "'");
            assertThat(imported.getExitCode()).as(imported.getStderr()).isZero();

            DriverManagerDataSource target = new DriverManagerDataSource(restored.getJdbcUrl(),
                    MySqlContainerConfiguration.MIGRATION_USER, migrationPassword);
            Flyway.configure().dataSource(target).locations("classpath:db/migration").load().validate();

            JdbcTemplate copy = new JdbcTemplate(target);
            for (String kind : List.of("BASE TABLE", "VIEW")) {
                assertThat(objects(copy, schema, kind)).as(kind).isEqualTo(objects(migration.jdbc(), schema, kind));
            }
            assertThat(triggers(copy, schema)).isEqualTo(triggers(migration.jdbc(), schema));
            for (String table : COUNTED) {
                assertThat(count(copy, table)).as(table).isEqualTo(count(migration.jdbc(), table));
            }
        } finally {
            jdbc.update("DELETE FROM backup_log WHERE id = ?", backup.id());
            Files.deleteIfExists(backupDirectory.resolve(backup.fileName()));
        }
    }

    private static List<String> objects(JdbcTemplate db, String schema, String kind) {
        return db.queryForList("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type = ?
                ORDER BY table_name""", String.class, schema, kind);
    }

    private static List<String> triggers(JdbcTemplate db, String schema) {
        return db.queryForList("SELECT trigger_name FROM information_schema.triggers WHERE trigger_schema = ? "
                + "ORDER BY trigger_name", String.class, schema);
    }

    private static long count(JdbcTemplate db, String table) {
        return db.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Long.class);
    }
}
