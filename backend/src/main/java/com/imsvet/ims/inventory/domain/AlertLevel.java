package com.imsvet.ims.inventory.domain;

import java.util.Locale;

/** Expiry alert levels (BR-LOT-010, ADR-056); no level means more days left than the notice threshold. */
public enum AlertLevel {
    EXPIRED, CRITICAL, WARNING, NOTICE;

    /** The API value. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
