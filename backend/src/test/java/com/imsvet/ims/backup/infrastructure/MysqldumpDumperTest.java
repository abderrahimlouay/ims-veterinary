package com.imsvet.ims.backup.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.backup.infrastructure.DatabaseDumper.BackupFailure;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit test (U) of the host {@code mysqldump} call (ADR-144): a stand-in program shows the arguments it receives and
 * the options file; the password is never an argument, the options file is private and deleted, a failure keeps the
 * program's message with the password masked.
 */
class MysqldumpDumperTest {

    private static final String SECRET = "s3cr\"et-pass";

    @TempDir
    private Path dir;

    @Test
    void passesTheAccountInAPrivateOptionsFileAndTheSchemaFromTheJdbcUrl() throws Exception {
        Path program = program("""
                #!/bin/sh
                options="${1#--defaults-extra-file=}"
                echo "ARGS $*"
                echo "MODE $(stat -c %a "$options")"
                cat "$options"
                """ + "echo \"$options\" > '" + dir.resolve("options-path") + "'\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        dumper(program).dump(out);

        String text = out.toString(StandardCharsets.UTF_8);
        String args = text.lines().filter(l -> l.startsWith("ARGS ")).findFirst().orElseThrow();
        assertThat(args).contains("--host=db.example").contains("--port=3307").contains("--single-transaction")
                .contains("--routines").contains("--triggers").contains("--no-tablespaces")
                .contains("--set-gtid-purged=OFF").endsWith("--databases ims").doesNotContain("s3cr");
        assertThat(text).contains("MODE 600").contains("user=\"ims_backup\"").contains("password=\"s3cr\\\"et-pass\"");
        Path options = Path.of(Files.readString(dir.resolve("options-path")).strip());
        assertThat(options).doesNotExist();
    }

    @Test
    void aFailureKeepsTheMessageWithoutThePassword() throws Exception {
        Path program = program("""
                #!/bin/sh
                echo "Access denied, tried s3cr\\"et-pass" >&2
                exit 2
                """);
        assertThatThrownBy(() -> dumper(program).dump(new ByteArrayOutputStream()))
                .isInstanceOf(BackupFailure.class)
                .hasMessage("mysqldump stopped with code 2: Access denied, tried ***");
    }

    @Test
    void aMissingProgramIsAFailure() {
        assertThatThrownBy(() -> dumper(dir.resolve("absent")).dump(new ByteArrayOutputStream()))
                .isInstanceOf(BackupFailure.class).hasMessageStartingWith("mysqldump cannot be started");
    }

    private MysqldumpDumper dumper(Path program) {
        return new MysqldumpDumper(new BackupProperties(dir.toString(), program.toString(), "ims_backup", SECRET),
                "jdbc:mysql://db.example:3307/ims?sslMode=REQUIRED");
    }

    private Path program(String script) throws Exception {
        Path program = dir.resolve("mysqldump");
        Files.writeString(program, script);
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
        return program;
    }
}
