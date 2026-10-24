package com.imsvet.ims.purchasing.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** FR-PURCH-001: laboratories and wholesalers (ADR-100). */
public enum SupplierKind {
    LABORATORY, WHOLESALER;

    /** The stored and API value. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<SupplierKind> fromCode(String code) {
        return Arrays.stream(values()).filter(k -> k.code().equals(code)).findFirst();
    }
}
