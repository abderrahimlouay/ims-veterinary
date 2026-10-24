package com.imsvet.ims.inventory.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * ADR-077, ADR-102: the suggested reorder quantity is {@code max_stock − sellable − outstanding} in base units
 * ({@code outstanding}: ordered and not yet received on open orders), rounded up to a whole number for a
 * non-divisible product (ADR-050). Empty without a max stock (reported thresholds incomplete) or when the max is
 * already reached with what is on order; no quantity is invented. A max at or below the reorder point never gets here (thresholds
 * inconsistent).
 */
public final class ReorderQuantity {

    private static final int QUANTITY_SCALE = 3;

    private ReorderQuantity() {
    }

    public static Optional<BigDecimal> suggested(BigDecimal maxStock, BigDecimal sellable, BigDecimal outstanding,
            boolean divisible) {
        if (maxStock == null) {
            return Optional.empty();
        }
        BigDecimal missing = maxStock.subtract(sellable).subtract(outstanding);
        if (!divisible) {
            missing = missing.setScale(0, RoundingMode.CEILING);
        }
        return missing.signum() > 0 ? Optional.of(missing.setScale(QUANTITY_SCALE, RoundingMode.CEILING))
                : Optional.empty();
    }
}
