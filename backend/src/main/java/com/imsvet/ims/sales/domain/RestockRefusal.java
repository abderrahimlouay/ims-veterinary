package com.imsvet.ims.sales.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * BR-RET-002, ADR-129: why returned goods may not go back into their lot. Sealed goods go back into a lot that is not
 * expired on the return date, whatever its stored status (a blocked or recalled lot stays unsellable); a product whose
 * returned units must stay out of sellable stock goes back only into a recalled lot, never sold again.
 */
public enum RestockRefusal {
    NOT_SEALED, LOT_EXPIRED, PRODUCT_RESTRICTED;

    /** The API value. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** {@code lotStatus}: the lot's stored status ({@code active}, {@code blocked}, {@code recalled}). */
    public static Optional<RestockRefusal> of(boolean sealed, boolean lotExpired, String lotStatus,
            boolean productRestricted) {
        if (!sealed) {
            return Optional.of(NOT_SEALED);
        }
        if (lotExpired) {
            return Optional.of(LOT_EXPIRED);
        }
        if (productRestricted && !"recalled".equals(lotStatus)) {
            return Optional.of(PRODUCT_RESTRICTED);
        }
        return Optional.empty();
    }
}
