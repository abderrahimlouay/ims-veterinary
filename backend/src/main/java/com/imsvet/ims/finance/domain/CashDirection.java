package com.imsvet.ims.finance.domain;

import java.util.Locale;

/** BR-CASH-005. */
public enum CashDirection {
    IN, OUT;

    /** The stored value. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static CashDirection fromCode(String code) {
        return valueOf(code.toUpperCase(Locale.ROOT));
    }
}
