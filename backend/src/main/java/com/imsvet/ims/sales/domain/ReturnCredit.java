package com.imsvet.ims.sales.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * ADR-130: what a credit-note line credits for {@code returnedBase} of a sold line. A partial return credits the
 * pro-rata of the line's unrounded amount, {@code quantity × unitPrice × (1 − discount) × returned ÷ sold}, HALF_UP to
 * 2 decimals, and its VAT per line (ADR-115); never more than what is left to credit. The return that completes the
 * line credits the exact remainder, so a fully returned line is credited exactly what was invoiced. The credited
 * quantity is in the sold unit, 3 decimals.
 */
public final class ReturnCredit {

    /** {@code ht} and {@code vat} of the credit-note line; {@code quantity} in the sold unit. */
    public record Credit(BigDecimal quantity, BigDecimal ht, BigDecimal vat) {
    }

    /** What a sold line is and what was already returned and credited of it. */
    public record SoldLine(BigDecimal quantity, BigDecimal quantityBase, BigDecimal unitPrice, BigDecimal discountPct,
            BigDecimal vatRate, BigDecimal returnedBase, BigDecimal creditedHt, BigDecimal creditedVat) {
    }

    private static final int MONEY_SCALE = 2;
    private static final int QUANTITY_SCALE = 3;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final MathContext PRECISE = MathContext.DECIMAL128;
    /** A credit-note line always shows a positive quantity, even for a tiny part of a large unit. */
    private static final BigDecimal SMALLEST_QUANTITY = new BigDecimal("0.001");

    private ReturnCredit() {
    }

    /** {@code returnedBase} > 0 and at most what is left to return: the caller checks BR-RET-001 first. */
    public static Credit of(SoldLine sold, BigDecimal returnedBase) {
        BigDecimal lineHt = SaleAmounts.lineHt(sold.quantity(), sold.unitPrice(), sold.discountPct());
        BigDecimal lineVat = SaleAmounts.lineVat(lineHt, sold.vatRate());
        BigDecimal leftHt = lineHt.subtract(sold.creditedHt());
        BigDecimal leftVat = lineVat.subtract(sold.creditedVat());
        BigDecimal quantity = sold.quantity().multiply(returnedBase).divide(sold.quantityBase(), PRECISE)
                .setScale(QUANTITY_SCALE, RoundingMode.HALF_UP).max(SMALLEST_QUANTITY);
        if (sold.returnedBase().add(returnedBase).compareTo(sold.quantityBase()) == 0) {
            return new Credit(quantity, leftHt, leftVat);
        }
        BigDecimal keep = BigDecimal.ONE.subtract(sold.discountPct().divide(HUNDRED));
        BigDecimal ht = sold.quantity().multiply(sold.unitPrice()).multiply(keep).multiply(returnedBase)
                .divide(sold.quantityBase(), PRECISE).setScale(MONEY_SCALE, RoundingMode.HALF_UP).min(leftHt);
        BigDecimal vat = SaleAmounts.lineVat(ht, sold.vatRate()).min(leftVat);
        return new Credit(quantity, ht, vat);
    }
}
