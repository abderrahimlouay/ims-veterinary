package com.imsvet.ims.inventory.domain;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * The status shown for a lot (ADR-059): the first that applies of recalled, blocked, expired, depleted, active.
 * Expired and depleted are computed on every read, never stored.
 */
public enum EffectiveLotStatus {
    ACTIVE, BLOCKED, RECALLED, EXPIRED, DEPLETED;

    public static EffectiveLotStatus of(LotStatus stored, boolean expired, BigDecimal quantityOnHand) {
        if (stored == LotStatus.RECALLED) {
            return RECALLED;
        }
        if (stored == LotStatus.BLOCKED) {
            return BLOCKED;
        }
        if (expired) {
            return EXPIRED;
        }
        return quantityOnHand.signum() == 0 ? DEPLETED : ACTIVE;
    }

    /** The API value. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
