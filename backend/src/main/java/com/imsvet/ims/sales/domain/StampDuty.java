package com.imsvet.ims.sales.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The stamp duty (droit de timbre) of an immediate invoice or receipt on its cash part (ADR-172). Every figure comes
 * from the settings; none is a constant here (CLAUDE.md §11). Nothing at or below {@code threshold}; otherwise the cash
 * part rounded up to a whole number of {@code step}, times {@code ratePercent} %, HALF_UP to 0.01, then kept between
 * {@code minimum} and {@code maximum} ({@code null}: no maximum; the maximum wins over a larger minimum).
 */
public record StampDuty(BigDecimal ratePercent, BigDecimal step, BigDecimal minimum, BigDecimal maximum,
        BigDecimal threshold, boolean onReceipts) {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public StampDuty {
        if (ratePercent.signum() <= 0 || step.signum() <= 0 || minimum.signum() < 0 || threshold.signum() < 0
                || (maximum != null && maximum.signum() < 0)) {
            throw new IllegalArgumentException("Invalid stamp-duty scale");
        }
    }

    /** Whether a document of this type, credit or not, carries stamp duty at all. */
    public boolean appliesTo(SalesDocType type, boolean creditSale) {
        if (creditSale) {
            return false;
        }
        return type == SalesDocType.INVOICE || (type == SalesDocType.RECEIPT && onReceipts);
    }

    /** The stamp duty on {@code cashPart}, scale 2; zero when there is no cash or it is at or below the threshold. */
    public BigDecimal on(BigDecimal cashPart) {
        if (cashPart.signum() <= 0 || cashPart.compareTo(threshold) <= 0) {
            return ZERO;
        }
        BigDecimal steps = cashPart.divide(step, 0, RoundingMode.CEILING);
        BigDecimal duty = steps.multiply(step).multiply(ratePercent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        duty = duty.max(minimum.setScale(2, RoundingMode.HALF_UP));
        if (maximum != null) {
            duty = duty.min(maximum.setScale(2, RoundingMode.HALF_UP));
        }
        return duty;
    }
}
