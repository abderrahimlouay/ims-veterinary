package com.imsvet.ims.inventory.domain;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Locale;

/**
 * The ten movement types of ADR-081 and their sign (BR-STOCK-005): entries positive, exits negative, inventory
 * regularisation either way; every adjustment carries a reason (BR-STOCK-006). Same rules as the database CHECKs.
 */
public enum MovementType {
    PURCHASE_RECEIPT(1), SALE(-1), CUSTOMER_RETURN(1), SUPPLIER_RETURN(-1), ADJUSTMENT_BREAKAGE(-1),
    ADJUSTMENT_EXPIRY(-1), ADJUSTMENT_THEFT(-1), ADJUSTMENT_INTERNAL_USE(-1), ADJUSTMENT_INVENTORY(0), OPENING_STOCK(1);

    /** 1: positive only; -1: negative only; 0: either sign. */
    private final int sign;

    MovementType(int sign) {
        this.sign = sign;
    }

    /** Never zero; otherwise the sign of the type. */
    public boolean allows(BigDecimal quantity) {
        return quantity.signum() != 0 && (sign == 0 || quantity.signum() == sign);
    }

    public boolean needsReason() {
        return name().startsWith("ADJUSTMENT_");
    }

    /** The stored and API value. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static MovementType fromCode(String code) {
        return Arrays.stream(values()).filter(t -> t.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown movement type " + code));
    }
}
