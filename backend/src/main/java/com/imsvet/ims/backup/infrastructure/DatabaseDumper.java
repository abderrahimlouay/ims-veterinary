package com.imsvet.ims.backup.infrastructure;

import java.io.IOException;
import java.io.OutputStream;

/** Writes a full logical dump of the application schema (SQL text) to {@code out} (ADR-144). */
public interface DatabaseDumper {

    /** Throws {@link BackupFailure} with a reason fit for the log and the screen, never a secret. */
    void dump(OutputStream out) throws IOException;

    /** A dump that did not complete; the message is shown to administrators. */
    class BackupFailure extends RuntimeException {

        public BackupFailure(String message) {
            super(message);
        }
    }
}
