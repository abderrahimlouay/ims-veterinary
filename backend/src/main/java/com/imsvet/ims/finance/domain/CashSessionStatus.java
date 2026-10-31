package com.imsvet.ims.finance.domain;

import java.util.Locale;

/** {@code open → closed}; a closed session never changes (BR-CASH-004). */
public enum CashSessionStatus {
    OPEN, CLOSED;

    /** The stored and API value. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static CashSessionStatus fromCode(String code) {
        return valueOf(code.toUpperCase(Locale.ROOT));
    }
}
