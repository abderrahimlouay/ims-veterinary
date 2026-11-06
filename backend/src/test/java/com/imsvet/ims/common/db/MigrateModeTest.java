package com.imsvet.ims.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.ImsApplication;
import com.imsvet.ims.MySqlContainerConfiguration;
import com.imsvet.ims.TestcontainersConfiguration;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * ADR-151 at a real start of the release JAR's code: the {@code prod,migrate} profiles migrate an empty schema to the
 * last version under the migration account, grant the application account (ADR-087), open no HTTP port and close by
 * themselves; the normal {@code prod} start then runs without any migration account variable, listening on the
 * loopback address only, behind the reverse proxy (ADR-152).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MigrateModeTest {

    private static final String SCHEMA = "ims_migrate_mode_check";
    private static final String APP_USER = "ims_release_app";
    private static final String APP_PASSWORD = "tc-release-" + UUID.randomUUID();

    @Autowired
    private MySQLContainer mysql;

    private JdbcTemplate root;

    @BeforeEach
    void emptySchemaAndAccount() {
        root = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), "root",
                MySqlContainerConfiguration.MYSQL_PASSWORD));
        dropSchemaAndAccount();
        root.execute("CREATE DATABASE " + SCHEMA);
        root.execute("CREATE USER '" + APP_USER + "'@'%' IDENTIFIED BY '" + APP_PASSWORD + "'");
    }

    @AfterEach
    void dropSchemaAndAccount() {
        root.execute("DROP DATABASE IF EXISTS " + SCHEMA);
        root.execute("DROP USER IF EXISTS '" + APP_USER + "'@'%'");
    }

    @Test
    void migrateModeMigratesGrantsAndClosesThenTheNormalStartNeedsNoMigrationAccount() {
        try (ConfigurableApplicationContext migrate = start("prod,migrate", "--IMS_DB_MIGRATION_USERNAME=root",
                "--IMS_DB_MIGRATION_PASSWORD=" + MySqlContainerConfiguration.MYSQL_PASSWORD)) {
            assertThat(migrate.isActive()).as("closed by the migrate mode").isFalse();
        }
        JdbcTemplate schema = new JdbcTemplate(new DriverManagerDataSource(url(), "root",
                MySqlContainerConfiguration.MYSQL_PASSWORD));
        assertThat(schema.queryForObject("""
                SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1""",
                String.class)).isEqualTo(lastMigration());
        List<String> grants = root.queryForList("SHOW GRANTS FOR '" + APP_USER + "'@'%'", String.class);
        assertThat(grants).anyMatch(g -> g.contains("`" + SCHEMA + "`.`v_sales_margin`") && g.startsWith("GRANT SELECT ON"))
                .anyMatch(g -> g.contains("`" + SCHEMA + "`.`stock_movements`") && g.startsWith("GRANT SELECT, INSERT ON"));

        try (ConfigurableApplicationContext running = start("prod", "--server.port=0", "--IMS_DB_APPLICATION_HOST=127.0.0.1")) {
            assertThat(running.getEnvironment().getProperty("ims.db.application-host")).isEqualTo("127.0.0.1");
            assertThat(running.isActive()).isTrue();
            assertThat(running.getEnvironment().getProperty("IMS_DB_MIGRATION_USERNAME")).isNull();
            assertThat(running.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
            assertThat(running.getEnvironment().getProperty("server.shutdown")).isEqualTo("graceful");
        }
    }

    private ConfigurableApplicationContext start(String profiles, String... extra) {
        String[] base = {"--spring.profiles.active=" + profiles, "--IMS_DB_URL=" + url(), "--IMS_DB_USERNAME=" + APP_USER,
            "--IMS_DB_PASSWORD=" + APP_PASSWORD, "--ims.jobs.enabled=false"};
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        return new SpringApplication(ImsApplication.class).run(args);
    }

    private String url() {
        return "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/" + SCHEMA;
    }

    /** The highest {@code V<phase>_<seq>} of the classpath migrations, as Flyway writes it ({@code 11.010}). */
    private static String lastMigration() {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            return Arrays.stream(resolver.getResources("classpath:db/migration/V*.sql"))
                    .map(r -> r.getFilename().substring(1, r.getFilename().indexOf("__")).replace('_', '.'))
                    .max(String::compareTo).orElseThrow();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
