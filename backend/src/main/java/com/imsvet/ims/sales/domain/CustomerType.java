package com.imsvet.ims.sales.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** BR-CUST-001: the three kinds of customer. */
public enum CustomerType {
    BREEDER, VETERINARIAN, OTHER;

    /** The stored and API value. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<CustomerType> fromCode(String code) {
        return Arrays.stream(values()).filter(t -> t.code().equals(code)).findFirst();
    }
}
