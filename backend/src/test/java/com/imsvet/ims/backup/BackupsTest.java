package com.imsvet.ims.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.backup.BackupTestConfiguration.ContainerDumper;
import com.imsvet.ims.backup.application.BackupJob;
import com.imsvet.ims.backup.application.BackupRunner;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * BR-BKP-001, BR-BKP-002, ADR-144…146: Run now writes a gzip dump of the whole schema and one log row, audited; a
 * failure is a record with its reason; the last {@code backup_retention_count} successful files are kept, the older
 * rows marked removed; a download is by record id, audited, and feeds the off-site reminder; one backup at a time; the
 * daily job runs once per business day from {@code backup_time}; the status lists the warnings of the backups page.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, BackupTestConfiguration.class})
@Transactional
class BackupsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    @Autowired
    private Path backupDirectory;

    @Autowired
    private ContainerDumper dumper;

    @Autowired
    private BackupRunner runner;

    @Autowired
    private BackupJob job;

    @PersistenceContext
    private EntityManager entities;

    private ApiClient admin;
    private TestUser user;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        user = new TestUsers(jdbc, encoder).create("Administrator");
        login();
    }

    /** Again after the clock moves past the session's absolute timeout. */
    private void login() throws Exception {
        admin = ApiClient.create(context, "203.0.113.41");
        admin.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void runNowWritesACompressedDumpOfTheWholeSchemaAndIsAudited() throws Exception {
        long id = id(admin.post("/api/backups", "").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.trigger").value("manual"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.fileName").value(org.hamcrest.Matchers.matchesPattern(
                        "ims-backup-\\d{8}-\\d{6}-\\d+\\.sql\\.gz"))));

        String file = jdbc.queryForObject("SELECT file_name FROM backup_log WHERE id = ?", String.class, id);
        String sql = gunzip(Files.readAllBytes(backupDirectory.resolve(file)));
        assertThat(sql).contains("CREATE TABLE `stock_movements`").contains("CREATE TABLE `flyway_schema_history`")
                .contains("trg_stock_movements_no_update").contains("v_sales_margin")
                .contains("INSERT INTO `permissions`");
        assertThat(jdbc.queryForObject("SELECT size_bytes FROM backup_log WHERE id = ?", Long.class, id))
                .isEqualTo(Files.size(backupDirectory.resolve(file)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'BACKUP_RUN' AND entity_id = ?",
                Integer.class, id)).isEqualTo(1);
        assertThat(Files.list(backupDirectory).filter(p -> p.toString().endsWith(".partial"))).isEmpty();
        admin.get("/api/backups").andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(id));
    }

    @Test
    void aFailedDumpIsRecordedWithItsReasonAndKeepsNoFile() throws Exception {
        long before;
        try (var files = Files.list(backupDirectory)) {
            before = files.count();
        }
        dumper.failNext("mysqldump stopped with code 2: Access denied");
        long id = id(admin.post("/api/backups", "").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.failureReason").value("mysqldump stopped with code 2: Access denied"))
                .andExpect(jsonPath("$.available").value(false)));
        try (var files = Files.list(backupDirectory)) {
            assertThat(files.count()).isEqualTo(before);
        }
        admin.get("/api/backups/" + id + "/download").andExpect(status().isNotFound());
        admin.get("/api/backups/status").andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.lastRun.id").value(id))
                .andExpect(jsonPath("$.warnings").value(org.hamcrest.Matchers.hasItem("LAST_FAILED")));
    }

    @Test
    void theNewestSuccessfulBackupsAreKeptAndADownloadIsAudited() throws Exception {
        jdbc.update("UPDATE app_settings SET setting_value = '2' WHERE setting_key = 'backup_retention_count'");
        long first = id(admin.post("/api/backups", "").andExpect(status().isCreated()));
        long second = id(admin.post("/api/backups", "").andExpect(status().isCreated()));
        long third = id(admin.post("/api/backups", "").andExpect(status().isCreated()));

        String firstFile = jdbc.queryForObject("SELECT file_name FROM backup_log WHERE id = ?", String.class, first);
        assertThat(backupDirectory.resolve(firstFile)).doesNotExist();
        assertThat(jdbc.queryForObject("SELECT removed_at IS NOT NULL FROM backup_log WHERE id = ?", Boolean.class,
                first)).isTrue();
        admin.get("/api/backups/" + first + "/download").andExpect(status().isNotFound());

        byte[] body = admin.get("/api/backups/" + third + "/download").andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/gzip"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(gunzip(body)).contains("CREATE TABLE `backup_log`");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'BACKUP_DOWNLOAD' AND entity_id = ?", Integer.class,
                third)).isEqualTo(1);
        admin.get("/api/backups/" + second + "/download").andExpect(status().isOk());
        admin.get("/api/backups/status").andExpect(jsonPath("$.lastDownloadAt").isNotEmpty())
                .andExpect(jsonPath("$.downloadReminderDays").value(7))
                .andExpect(jsonPath("$.warnings").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("DOWNLOAD_OVERDUE"))))
                .andExpect(jsonPath("$.warnings").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("NO_RECENT_BACKUP"))));

        clock.advance(Duration.ofDays(8));
        login();
        admin.get("/api/backups/status").andExpect(jsonPath("$.warnings").value(
                org.hamcrest.Matchers.hasItems("DOWNLOAD_OVERDUE", "NO_RECENT_BACKUP")));
        admin.get("/api/backups/999999/download").andExpect(status().isNotFound());
    }

    @Test
    void theDailyJobRunsOncePerBusinessDayFromItsTime() {
        long before = scheduledRuns();
        jdbc.update("UPDATE app_settings SET setting_value = '10:00' WHERE setting_key = 'backup_time'");
        job.tick();
        assertThat(scheduledRuns()).isEqualTo(before);

        jdbc.update("UPDATE app_settings SET setting_value = '09:00' WHERE setting_key = 'backup_time'");
        entities.clear();
        job.tick();
        job.tick();
        assertThat(scheduledRuns()).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("""
                SELECT started_by IS NULL FROM backup_log WHERE trigger_type = 'scheduled' ORDER BY id DESC LIMIT 1""",
                Boolean.class)).isTrue();

        clock.advance(Duration.ofDays(1));
        job.tick();
        assertThat(scheduledRuns()).isEqualTo(before + 2);
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void oneBackupAtATime() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        dumper.holdNext(started, release);
        CompletableFuture<Long> first = CompletableFuture.supplyAsync(() -> runner.run(null).id());
        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> runner.run(null)).isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already running");
        } finally {
            release.countDown();
        }
        long id = first.get(60, TimeUnit.SECONDS);
        String file = jdbc.queryForObject("SELECT file_name FROM backup_log WHERE id = ?", String.class, id);
        jdbc.update("DELETE FROM backup_log WHERE id = ?", id);
        Files.deleteIfExists(backupDirectory.resolve(file));
    }

    @Test
    void backupsNeedTheirPermission() throws Exception {
        TestUser accountant = new TestUsers(jdbc, encoder).create("Accountant");
        ApiClient client = ApiClient.create(context, "203.0.113.42");
        client.login(accountant.username(), accountant.password()).andExpect(status().isOk());
        client.get("/api/backups").andExpect(status().isForbidden());
        client.get("/api/backups/status").andExpect(status().isForbidden());
        client.post("/api/backups", "").andExpect(status().isForbidden());
        client.get("/api/backups/1/download").andExpect(status().isForbidden());
    }

    @Test
    void theBackupAccountOnlyReads(@Autowired org.testcontainers.mysql.MySQLContainer mysql,
            @Autowired com.imsvet.ims.support.BackupAccount account) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(mysql.getJdbcUrl(), account.username(),
                account.password()); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM permissions")) {
                assertThat(rows.next()).isTrue();
            }
            assertThatThrownBy(() -> sql.executeUpdate("DELETE FROM app_settings WHERE setting_key = 'locale'"))
                    .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("denied");
            assertThatThrownBy(() -> sql.execute("CREATE TABLE intruder (id INT)"))
                    .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("denied");
        }
    }

    private long scheduledRuns() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM backup_log WHERE trigger_type = 'scheduled'", Long.class);
    }

    private static String gunzip(byte[] bytes) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static long id(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
