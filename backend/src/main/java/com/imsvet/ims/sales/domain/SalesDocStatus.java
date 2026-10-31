package com.imsvet.ims.sales.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** {@code draft → confirmed}, {@code draft → cancelled} (abandoned) and {@code confirmed → cancelled} (ADR-119). */
public enum SalesDocStatus {
    DRAFT, CONFIRMED, CANCELLED;

    /** The stored and API value. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<SalesDocStatus> fromCode(String code) {
        return Arrays.stream(values()).filter(s -> s.code().equals(code)).findFirst();
    }
}
