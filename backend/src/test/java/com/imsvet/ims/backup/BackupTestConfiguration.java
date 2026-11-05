package com.imsvet.ims.backup;

import com.imsvet.ims.backup.infrastructure.DatabaseDumper;
import com.imsvet.ims.support.BackupAccount;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Backups against the test database (ADR-144): the real {@code mysqldump} of the MySQL image, run inside the container
 * under the read-only backup account (the host may have no {@code mysqldump}; the host process path is covered by
 * {@code MysqldumpDumperTest}); a fresh backup folder per context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class BackupTestConfiguration {

    @Bean
    Path backupDirectory() throws IOException {
        return Files.createTempDirectory("ims-backups-");
    }

    @Bean
    DynamicPropertyRegistrar backupProperties(Path backupDirectory, BackupAccount account) {
        return registry -> {
            registry.add("ims.backup.directory", backupDirectory::toString);
            registry.add("ims.backup.username", account::username);
            registry.add("ims.backup.password", account::password);
        };
    }

    @Bean
    @Primary
    ContainerDumper containerDumper(MySQLContainer mysql, BackupAccount account) {
        return new ContainerDumper(mysql, account);
    }

    /** {@code mysqldump} in the container; can be told to fail or to wait, for the failure and one-at-a-time tests. */
    public static class ContainerDumper implements DatabaseDumper {

        private final MySQLContainer mysql;
        private final BackupAccount account;
        private volatile String failWith;
        private volatile CountDownLatch started;
        private volatile CountDownLatch release;

        ContainerDumper(MySQLContainer mysql, BackupAccount account) {
            this.mysql = mysql;
            this.account = account;
        }

        public void failNext(String reason) {
            failWith = reason;
        }

        /** The next dump signals {@code started} then waits for {@code release}. */
        public void holdNext(CountDownLatch started, CountDownLatch release) {
            this.started = started;
            this.release = release;
        }

        @Override
        public void dump(OutputStream out) throws IOException {
            String failure = failWith;
            failWith = null;
            if (failure != null) {
                throw new BackupFailure(failure);
            }
            CountDownLatch go = release;
            if (go != null) {
                release = null;
                started.countDown();
                try {
                    go.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                var result = mysql.execInContainer("mysqldump", "--user=" + account.username(),
                        "--password=" + account.password(), "--single-transaction", "--routines", "--triggers",
                        "--no-tablespaces", "--set-gtid-purged=OFF", "--hex-blob", "--default-character-set=utf8mb4",
                        "--databases", mysql.getDatabaseName());
                if (result.getExitCode() != 0) {
                    throw new BackupFailure("mysqldump stopped with code " + result.getExitCode() + ": "
                            + result.getStderr().replace(account.password(), "***").strip());
                }
                out.write(result.getStdout().getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BackupFailure("The backup was interrupted.");
            }
        }
    }
}
