package com.imsvet.ims.sales.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** BR-RET-002/003: back into the original lot, or logged in and destroyed at once (ADR-127, ADR-129). */
public enum ReturnDisposition {
    RESTOCK, DISCARD;

    /** The stored and API value. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<ReturnDisposition> fromCode(String code) {
        return Arrays.stream(values()).filter(d -> d.code().equals(code)).findFirst();
    }
}
