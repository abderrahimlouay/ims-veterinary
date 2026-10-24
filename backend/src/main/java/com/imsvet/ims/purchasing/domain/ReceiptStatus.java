package com.imsvet.ims.purchasing.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** A receipt is created confirmed and may be cancelled as a whole (ADR-103). */
public enum ReceiptStatus {
    CONFIRMED, CANCELLED;

    /** The stored and API value. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<ReceiptStatus> fromCode(String code) {
        return Arrays.stream(values()).filter(s -> s.code().equals(code)).findFirst();
    }
}
