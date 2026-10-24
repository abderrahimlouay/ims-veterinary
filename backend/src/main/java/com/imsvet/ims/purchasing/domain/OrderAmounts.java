package com.imsvet.ims.purchasing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * ADR-093: amounts per line, HALF_UP, with the formula of the generated columns {@code line_ht} and
 * {@code line_vat} (ADR-016 parity): {@code ht = round(quantity × price × (1 − discount/100), 2)},
 * {@code vat = round(ht × rate/100, 2)}; document totals are sums of the rounded lines.
 */
public final class OrderAmounts {

    private static final int MONEY_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private OrderAmounts() {
    }

    public static BigDecimal lineHt(BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPct) {
        BigDecimal keep = BigDecimal.ONE.subtract(discountPct.divide(HUNDRED));
        return quantity.multiply(unitPrice).multiply(keep).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal lineVat(BigDecimal lineHt, BigDecimal vatRate) {
        return lineHt.multiply(vatRate).divide(HUNDRED).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
