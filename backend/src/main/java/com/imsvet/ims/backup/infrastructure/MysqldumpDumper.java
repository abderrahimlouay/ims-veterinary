package com.imsvet.ims.backup.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs {@code mysqldump} under the read-only backup account (ADR-144): one consistent snapshot
 * ({@code --single-transaction}), routines and triggers, no tablespaces (no PROCESS right needed), no GTID statement.
 * Host, port and schema come from the application's JDBC URL. The password goes to {@code mysqldump} in an options file
 * readable by the application user only, deleted afterwards: never on the command line, where other users could read
 * it. The program's error output becomes the failure reason, the password removed if it ever appeared.
 */
@Component
public class MysqldumpDumper implements DatabaseDumper {

    private static final Pattern JDBC = Pattern.compile("jdbc:mysql://([^:/?]+)(?::(\\d+))?/([^?]+).*");
    private static final int REASON_MAX = 400;

    private final BackupProperties properties;
    private final String jdbcUrl;

    public MysqldumpDumper(BackupProperties properties, @Value("${spring.datasource.url}") String jdbcUrl) {
        this.properties = properties;
        this.jdbcUrl = jdbcUrl;
    }

    @Override
    public void dump(OutputStream out) throws IOException {
        Matcher url = JDBC.matcher(jdbcUrl);
        if (!url.matches()) {
            throw new BackupFailure("The database address cannot be read for the backup.");
        }
        Path options = privateTempFile();
        try {
            Files.writeString(options, "[client]\nuser=\"" + escape(properties.username()) + "\"\npassword=\""
                    + escape(properties.password()) + "\"\n", StandardCharsets.UTF_8);
            Process process = start(options, url);
            try {
                CompletableFuture<String> errors = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
                try (InputStream dump = process.getInputStream()) {
                    dump.transferTo(out);
                }
                int exit = process.waitFor();
                if (exit != 0) {
                    throw new BackupFailure("mysqldump stopped with code " + exit + ": " + reason(errors.join()));
                }
            } finally {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackupFailure("The backup was interrupted.");
        } finally {
            Files.deleteIfExists(options);
        }
    }

    /**
     * An empty temporary file only its owner can read and write: {@code rw-------} where the file system is POSIX; on
     * Windows (the owner's laptop, ADR-165), which has no POSIX permissions, an access list naming the owner alone
     * (the service account), set before the password is written.
     */
    static Path privateTempFile() throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempFile("ims-backup-", ".cnf",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Path file = Files.createTempFile("ims-backup-", ".cnf");
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl == null) {
            Files.deleteIfExists(file);
            throw new BackupFailure("The backup options file cannot be made private on this system.");
        }
        acl.setAcl(List.of(AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(file))
                .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA,
                        AclEntryPermission.APPEND_DATA, AclEntryPermission.READ_ATTRIBUTES,
                        AclEntryPermission.WRITE_ATTRIBUTES, AclEntryPermission.READ_NAMED_ATTRS,
                        AclEntryPermission.WRITE_NAMED_ATTRS, AclEntryPermission.READ_ACL,
                        AclEntryPermission.DELETE, AclEntryPermission.SYNCHRONIZE)
                .build()));
        return file;
    }

    private Process start(Path options, Matcher url) {
        try {
            return new ProcessBuilder(command(options, url)).start();
        } catch (IOException e) {
            throw new BackupFailure("mysqldump cannot be started: " + e.getMessage());
        }
    }

    List<String> command(Path options, Matcher url) {
        List<String> command = new ArrayList<>();
        command.add(properties.mysqldump() == null || properties.mysqldump().isBlank() ? "mysqldump"
                : properties.mysqldump());
        command.add("--defaults-extra-file=" + options);
        command.add("--host=" + url.group(1));
        command.add("--port=" + (url.group(2) == null ? "3306" : url.group(2)));
        command.addAll(List.of("--protocol=TCP", "--single-transaction", "--routines", "--triggers",
                "--no-tablespaces", "--set-gtid-purged=OFF", "--hex-blob", "--default-character-set=utf8mb4",
                "--databases", url.group(3)));
        return command;
    }

    private String reason(String errors) {
        String text = errors == null || errors.isBlank() ? "no message" : errors.strip();
        if (properties.password() != null && !properties.password().isEmpty()) {
            text = text.replace(properties.password(), "***");
        }
        return text.length() > REASON_MAX ? text.substring(0, REASON_MAX) : text;
    }

    private static String read(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
