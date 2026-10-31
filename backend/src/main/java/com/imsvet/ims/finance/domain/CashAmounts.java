package com.imsvet.ims.finance.domain;

import java.math.BigDecimal;

/**
 * BR-CASH-002/003: expected = opening + cash in − cash out, cheques and transfers excluded by the caller's sums;
 * variance = counted − expected, as the generated column.
 */
public final class CashAmounts {

    private CashAmounts() {
    }

    public static BigDecimal expected(BigDecimal opening, BigDecimal cashIn, BigDecimal cashOut) {
        return opening.add(cashIn).subtract(cashOut);
    }

    public static BigDecimal variance(BigDecimal counted, BigDecimal expected) {
        return counted.subtract(expected);
    }
}
