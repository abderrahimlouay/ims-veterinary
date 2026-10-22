package com.imsvet.ims.catalog.domain;

import java.math.BigDecimal;

/**
 * BR-UNIT-002 and ADR-065: {@code quantity_base = quantity × factor_to_base}, exact with the 3 decimals of base
 * quantities and never rounded. The base unit has factor 1.
 */
public final class UnitConversion {

    /** Base quantities keep 3 decimals (database.md §2.3). */
    public static final int BASE_SCALE = 3;

    /** Why a conversion was refused; the caller maps it to a field error. */
    public enum Refusal {
        NOT_POSITIVE, SCALE, WHOLE_NUMBER
    }

    public static final class InexactConversionException extends RuntimeException {

        private final Refusal refusal;

        InexactConversionException(Refusal refusal) {
            super("Unit conversion refused: " + refusal);
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    private UnitConversion() {
    }

    /** The quantity in base units; {@code divisible} false requires a whole result (ADR-050). */
    public static BigDecimal toBase(BigDecimal quantity, BigDecimal factorToBase, boolean divisible) {
        BigDecimal base = quantity.multiply(factorToBase);
        if (base.signum() <= 0) {
            throw new InexactConversionException(Refusal.NOT_POSITIVE);
        }
        BigDecimal exact = base.stripTrailingZeros();
        if (exact.scale() > BASE_SCALE) {
            throw new InexactConversionException(Refusal.SCALE);
        }
        if (!divisible && exact.scale() > 0) {
            throw new InexactConversionException(Refusal.WHOLE_NUMBER);
        }
        return base.setScale(BASE_SCALE);
    }
}
