package com.imsvet.ims.backup.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The backup folder (ADR-144): files are written under a temporary name and renamed once complete, so a half-written
 * backup is never kept; a file is found by its recorded name only, which must match the pattern the application gives
 * (no path from a client, security.md path traversal).
 */
@Component
public class BackupFolder {

    private static final Pattern NAME = Pattern.compile("ims-backup-\\d{8}-\\d{6}-\\d+\\.sql\\.gz");

    private final BackupProperties properties;

    public BackupFolder(BackupProperties properties) {
        this.properties = properties;
    }

    /** A file being written; {@link Writing#keep} renames it to {@code name}, closing discards it. */
    public Writing write(String name) throws IOException {
        Path folder = folder();
        Files.createDirectories(folder);
        Path target = resolve(name);
        Path partial = folder.resolve(name + ".partial");
        return new Writing(partial, target, Files.newOutputStream(partial));
    }

    public InputStream open(String name) throws IOException {
        return Files.newInputStream(resolve(name));
    }

    public boolean exists(String name) {
        return Files.isRegularFile(resolve(name));
    }

    public void delete(String name) throws IOException {
        Files.deleteIfExists(resolve(name));
    }

    private Path folder() {
        return Path.of(properties.directory()).toAbsolutePath().normalize();
    }

    private Path resolve(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a backup file name");
        }
        return folder().resolve(name);
    }

    /** The output of a backup being written. */
    public static final class Writing implements AutoCloseable {

        private final Path partial;
        private final Path target;
        private final OutputStream out;
        private boolean kept;

        Writing(Path partial, Path target, OutputStream out) {
            this.partial = partial;
            this.target = target;
            this.out = out;
        }

        public OutputStream out() {
            return out;
        }

        /** Closes the output and gives the file its final name; returns its size in bytes. */
        public long keep() throws IOException {
            out.close();
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            kept = true;
            return Files.size(target);
        }

        @Override
        public void close() throws IOException {
            out.close();
            if (!kept) {
                Files.deleteIfExists(partial);
            }
        }
    }
}
