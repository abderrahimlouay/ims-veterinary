package com.imsvet.ims;

import com.imsvet.ims.common.db.MySqlVersion;
import com.imsvet.ims.support.MigrationAccount;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real MySQL (ADR-030), same release line as production (ADR-032), refused when older than 8.0.30 (ADR-003), with the two accounts of ADR-087: Flyway
 * migrates with {@link #MIGRATION_USER} and the application connects with the restricted {@link #APP_USER}, whose
 * rights come only from the grants callback. Used alone by {@link TestImsApplication}; tests get it through
 * {@link TestcontainersConfiguration}. Passwords are generated per run, never written anywhere.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlContainerConfiguration {

    public static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.4");

    public static final String APP_USER = "ims_app";

    public static final String MIGRATION_USER = "ims_migrator";

    /** Distinctive so tests can assert it never reaches the logs (ADR-031). */
    public static final String MYSQL_PASSWORD = "tc-canary-" + UUID.randomUUID();

    private static final String APP_PASSWORD = "tc-app-" + UUID.randomUUID();

    private static final String MIGRATION_PASSWORD = "tc-migration-" + UUID.randomUUID();

    @Bean
    MySQLContainer mysqlContainer() throws SQLException {
        // ADR-043: binary logging is on by default; this lets the non-SUPER user create triggers (ADR-017).
        MySQLContainer mysql = new MySQLContainer(MYSQL_IMAGE).withPassword(MYSQL_PASSWORD)
                .withCommand("--log-bin-trust-function-creators=1");
        mysql.start();
        try (Connection root = DriverManager.getConnection(mysql.getJdbcUrl(), "root", MYSQL_PASSWORD);
                Statement sql = root.createStatement(); ResultSet version = sql.executeQuery("SELECT VERSION()")) {
            version.next();
            MySqlVersion.requireSupported(version.getString(1));
        }
        createAccounts(mysql);
        return mysql;
    }

    @Bean
    DynamicPropertyRegistrar databaseAccounts(MySQLContainer mysql) {
        return registry -> {
            registry.add("spring.datasource.url", mysql::getJdbcUrl);
            registry.add("spring.datasource.username", () -> APP_USER);
            registry.add("spring.datasource.password", () -> APP_PASSWORD);
            registry.add("spring.flyway.url", mysql::getJdbcUrl);
            registry.add("spring.flyway.user", () -> MIGRATION_USER);
            registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
        };
    }

    @Bean
    MigrationAccount migrationAccount(MySQLContainer mysql) {
        return new MigrationAccount(new JdbcTemplate(
                new DriverManagerDataSource(mysql.getJdbcUrl(), MIGRATION_USER, MIGRATION_PASSWORD)));
    }

    /** As an administrator would (README): the migration account owns the schema; the application account starts empty. */
    private static void createAccounts(MySQLContainer mysql) throws SQLException {
        try (Connection root = DriverManager.getConnection(mysql.getJdbcUrl(), "root", MYSQL_PASSWORD);
                Statement sql = root.createStatement()) {
            sql.execute("CREATE USER '" + MIGRATION_USER + "'@'%' IDENTIFIED BY '" + MIGRATION_PASSWORD + "'");
            sql.execute("GRANT ALL PRIVILEGES ON `" + mysql.getDatabaseName() + "`.* TO '" + MIGRATION_USER
                    + "'@'%' WITH GRANT OPTION");
            sql.execute("CREATE USER '" + APP_USER + "'@'%' IDENTIFIED BY '" + APP_PASSWORD + "'");
        }
    }
}
