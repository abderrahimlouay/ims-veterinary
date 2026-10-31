package com.imsvet.ims.sales.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * ADR-093, ADR-115: amounts per line, HALF_UP, with the formula of the generated columns {@code line_ht},
 * {@code line_vat} and {@code line_ttc} (ADR-016 parity); document totals are sums of the rounded lines. The discount
 * total is what the lines' discounts took off the gross amount.
 */
public final class SaleAmounts {

    private static final int MONEY_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private SaleAmounts() {
    }

    public static BigDecimal lineHt(BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPct) {
        BigDecimal keep = BigDecimal.ONE.subtract(discountPct.divide(HUNDRED));
        return quantity.multiply(unitPrice).multiply(keep).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal lineVat(BigDecimal lineHt, BigDecimal vatRate) {
        return lineHt.multiply(vatRate).divide(HUNDRED).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal lineDiscount(BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineHt) {
        return quantity.multiply(unitPrice).setScale(MONEY_SCALE, RoundingMode.HALF_UP).subtract(lineHt);
    }
}
