package com.imsvet.ims.inventory.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * ADR-077: the suggested reorder quantity is {@code max_stock − sellable} in base units, rounded up to a whole number
 * for a non-divisible product (ADR-050). Empty without a max stock, or when the max is already reached: the product
 * is then reported thresholds incomplete, no quantity is invented.
 */
public final class ReorderQuantity {

    private static final int QUANTITY_SCALE = 3;

    private ReorderQuantity() {
    }

    public static Optional<BigDecimal> suggested(BigDecimal maxStock, BigDecimal sellable, boolean divisible) {
        if (maxStock == null) {
            return Optional.empty();
        }
        BigDecimal missing = maxStock.subtract(sellable);
        if (!divisible) {
            missing = missing.setScale(0, RoundingMode.CEILING);
        }
        return missing.signum() > 0 ? Optional.of(missing.setScale(QUANTITY_SCALE, RoundingMode.CEILING))
                : Optional.empty();
    }
}
