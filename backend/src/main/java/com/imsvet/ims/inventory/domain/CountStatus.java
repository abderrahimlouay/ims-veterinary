package com.imsvet.ims.inventory.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** An open count changes; a closed (regularised, ADR-075) or cancelled one never does (BR-INV-004). */
public enum CountStatus {
    OPEN, CLOSED, CANCELLED;

    /** The stored and API value. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<CountStatus> fromCode(String code) {
        return Arrays.stream(values()).filter(s -> s.code().equals(code)).findFirst();
    }
}
