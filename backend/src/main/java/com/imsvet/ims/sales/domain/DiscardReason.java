package com.imsvet.ims.sales.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** BR-RET-010, ADR-127: why returned goods are destroyed; {@code OTHER} needs a note. */
public enum DiscardReason {
    OPENED, PACKAGING_DAMAGED, EXPIRED, RECALLED, COLD_CHAIN_DOUBT, OTHER;

    /** The stored and API value. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<DiscardReason> fromCode(String code) {
        return Arrays.stream(values()).filter(r -> r.code().equals(code)).findFirst();
    }
}
