package com.imsvet.ims.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.ImsApplication;
import com.imsvet.ims.MySqlContainerConfiguration;
import com.imsvet.ims.TestcontainersConfiguration;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * ADR-151 at a real start of the release JAR's code: the {@code prod,migrate} profiles migrate an empty schema to the
 * last version under the migration account, grant the application account (ADR-087), open no HTTP port and close by
 * themselves, needing no certificate; the normal {@code prod} start then runs without any migration account variable
 * and serves HTTPS itself with the shop's certificate (ADR-166), plain HTTP only redirecting to it.
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

    @TempDir
    private Path tls;

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
    void migrateModeMigratesGrantsAndClosesThenTheNormalStartServesHttpsWithoutTheMigrationAccount() throws Exception {
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

        String keystorePassword = "tc-tls-" + UUID.randomUUID();
        Path keystore = selfSignedKeystore(keystorePassword);
        int https = freePort();
        int http = freePort();
        try (ConfigurableApplicationContext running = start("prod", "--IMS_DB_APPLICATION_HOST=127.0.0.1",
                "--IMS_SERVER_ADDRESS=127.0.0.1", "--IMS_SERVER_PORT=" + https, "--IMS_HTTP_PORT=" + http,
                "--IMS_TLS_KEYSTORE=" + keystore, "--IMS_TLS_KEYSTORE_PASSWORD=" + keystorePassword)) {
            assertThat(running.getEnvironment().getProperty("ims.db.application-host")).isEqualTo("127.0.0.1");
            assertThat(running.isActive()).isTrue();
            assertThat(running.getEnvironment().getProperty("IMS_DB_MIGRATION_USERNAME")).isNull();
            assertThat(running.getEnvironment().getProperty("server.shutdown")).isEqualTo("graceful");
            assertThat(running.getEnvironment().getProperty("server.forward-headers-strategy"))
                    .as("no proxy in front: no forwarded header trusted").isNull();

            HttpClient client = HttpClient.newBuilder().sslContext(trusting(keystore, keystorePassword))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            HttpResponse<String> health = client.send(HttpRequest.newBuilder(
                    URI.create("https://localhost:" + https + "/actuator/health")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.headers().firstValue("Strict-Transport-Security")).hasValueSatisfying(
                    v -> assertThat(v).startsWith("max-age=31536000"));
            HttpResponse<String> plain = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + http + "/products")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(plain.statusCode()).isBetween(301, 302);
            assertThat(plain.headers().firstValue("Location")).hasValue("https://localhost:" + https + "/products");
        }
    }

    /** A throw-away server certificate for localhost, as the shop's CA would sign one (deployment.md). */
    private Path selfSignedKeystore(String password) throws IOException, InterruptedException {
        Path keystore = tls.resolve("server.p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "ims", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", keystore.toString(), "-storepass", password).redirectErrorStream(true).start();
        keytool.getInputStream().readAllBytes();
        assertThat(keytool.waitFor()).as("keytool").isZero();
        return keystore;
    }

    private static SSLContext trusting(Path keystore, String password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(keystore.toFile())) {
            store.load(in, password.toCharArray());
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
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
