package com.imsvet.ims.inventory.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** BR-INV-003, ADR-073: {@code full} counts every lot with stock; {@code cycle} one category or a product list. */
public enum CountType {
    FULL, CYCLE;

    /** The stored and API value. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<CountType> fromCode(String code) {
        return Arrays.stream(values()).filter(t -> t.code().equals(code)).findFirst();
    }
}
