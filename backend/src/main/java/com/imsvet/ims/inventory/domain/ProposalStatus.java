package com.imsvet.ims.inventory.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** A reorder proposal is pending until dismissed or (Phase 5) converted to a purchase order (ADR-079). */
public enum ProposalStatus {
    PENDING, CONVERTED, DISMISSED;

    /** The stored and API value. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<ProposalStatus> fromCode(String code) {
        return Arrays.stream(values()).filter(s -> s.code().equals(code)).findFirst();
    }
}
