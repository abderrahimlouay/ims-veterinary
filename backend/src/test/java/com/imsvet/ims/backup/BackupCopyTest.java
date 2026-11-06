package com.imsvet.ims.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-167: with a copy folder (Google Drive for desktop in production), each successful backup is copied there, the
 * result kept in its row; a failed copy keeps the backup and warns; retention removes the copies too; the copy, not a
 * download, is then the off-site copy the page watches.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, BackupTestConfiguration.class, BackupCopyTest.CopyFolder.class})
@Transactional
class BackupCopyTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TestConfiguration(proxyBeanMethods = false)
    static class CopyFolder {

        @Bean
        Path backupCopyDirectory() throws IOException {
            return Files.createTempDirectory("ims-backup-copies-");
        }

        @Bean
        DynamicPropertyRegistrar backupCopyProperty(Path backupCopyDirectory) {
            return registry -> registry.add("ims.backup.copy-directory", backupCopyDirectory::toString);
        }
    }

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
    private Path backupCopyDirectory;

    private ApiClient admin;
    private TestUser user;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        user = new TestUsers(jdbc, encoder).create("Administrator");
        login();
    }

    @AfterEach
    void copyFolderBack() throws IOException {
        if (Files.isRegularFile(backupCopyDirectory)) {
            Files.delete(backupCopyDirectory);
        }
        Files.createDirectories(backupCopyDirectory);
    }

    private void login() throws Exception {
        admin = ApiClient.create(context, "203.0.113.51");
        admin.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void aSuccessfulBackupIsCopiedWholeAndTheCopyReplacesTheDownloadReminder() throws Exception {
        long id = id(admin.post("/api/backups", "").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.copyStatus").value("copied"))
                .andExpect(jsonPath("$.copyFailureReason").isEmpty()));
        String file = fileOf(id);
        assertThat(Files.readAllBytes(backupCopyDirectory.resolve(file)))
                .isEqualTo(Files.readAllBytes(backupDirectory.resolve(file)));
        try (Stream<Path> files = Files.list(backupCopyDirectory)) {
            assertThat(files.filter(p -> p.toString().endsWith(".partial"))).isEmpty();
        }
        admin.get("/api/backups/status").andExpect(status().isOk())
                .andExpect(jsonPath("$.copyConfigured").value(true))
                .andExpect(jsonPath("$.lastCopyAt").isNotEmpty())
                .andExpect(jsonPath("$.warnings").value(not(hasItem("DOWNLOAD_OVERDUE"))))
                .andExpect(jsonPath("$.warnings").value(not(hasItem("NO_RECENT_COPY"))))
                .andExpect(jsonPath("$.warnings").value(not(hasItem("COPY_FAILED"))));

        clock.advance(Duration.ofDays(2));
        login();
        admin.get("/api/backups/status").andExpect(jsonPath("$.warnings").value(hasItems("NO_RECENT_COPY",
                "NO_RECENT_BACKUP"))).andExpect(jsonPath("$.warnings").value(not(hasItem("DOWNLOAD_OVERDUE"))));
    }

    @Test
    void aFailedCopyKeepsTheBackupAndWarnsUntilACopySucceeds() throws Exception {
        Files.delete(backupCopyDirectory);
        Files.writeString(backupCopyDirectory, "not a folder");
        long id = id(admin.post("/api/backups", "").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.copyStatus").value("failed"))
                .andExpect(jsonPath("$.copyFailureReason").value(containsString("IMS_BACKUP_COPY_DIR"))));
        assertThat(backupDirectory.resolve(fileOf(id))).exists();
        admin.get("/api/backups/status").andExpect(jsonPath("$.warnings").value(hasItems("COPY_FAILED",
                "NO_RECENT_COPY"))).andExpect(jsonPath("$.warnings").value(not(hasItem("LAST_FAILED"))));

        copyFolderBack();
        admin.post("/api/backups", "").andExpect(jsonPath("$.copyStatus").value("copied"));
        admin.get("/api/backups/status").andExpect(jsonPath("$.warnings").value(not(hasItem("COPY_FAILED"))))
                .andExpect(jsonPath("$.warnings").value(not(hasItem("NO_RECENT_COPY"))));
    }

    @Test
    void retentionRemovesTheCopiesToo() throws Exception {
        jdbc.update("UPDATE app_settings SET setting_value = '1' WHERE setting_key = 'backup_retention_count'");
        long first = id(admin.post("/api/backups", "").andExpect(status().isCreated()));
        long second = id(admin.post("/api/backups", "").andExpect(status().isCreated()));
        assertThat(backupCopyDirectory.resolve(fileOf(first))).doesNotExist();
        assertThat(backupDirectory.resolve(fileOf(first))).doesNotExist();
        assertThat(backupCopyDirectory.resolve(fileOf(second))).exists();
    }

    @Test
    void aFailedBackupHasNoCopy() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO backup_log (trigger_type, status, failure_reason, copy_status, started_at, finished_at)
                VALUES ('scheduled', 'failed', 'x', 'copied', NOW(6), NOW(6))"""))
                .hasMessageContaining("ck_backup_log_copy");
    }

    private String fileOf(long id) {
        return jdbc.queryForObject("SELECT file_name FROM backup_log WHERE id = ?", String.class, id);
    }

    private static long id(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
