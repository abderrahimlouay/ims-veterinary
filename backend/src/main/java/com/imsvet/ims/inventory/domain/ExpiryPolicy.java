package com.imsvet.ims.inventory.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Expiry rules evaluated against one business date (ADR-055) and the configured thresholds in days (ADR-056):
 * expired ⇔ expiry date before the business date; levels inclusive; sellable ⇔ stored active, not expired and
 * stock on hand (BR-STOCK-011). Thresholds are strictly decreasing positive days.
 */
public record ExpiryPolicy(LocalDate businessDate, int notice, int warning, int critical) {

    /** Expiry dates of one level, inclusive; {@code from} {@code null} means no lower bound. */
    public record Window(LocalDate from, LocalDate to) {
    }

    public ExpiryPolicy {
        if (critical <= 0 || warning <= critical || notice <= warning) {
            throw new IllegalArgumentException("Thresholds must be strictly decreasing positive days");
        }
    }

    /** Negative once expired; 0 on the expiry date itself. */
    public long daysLeft(LocalDate expiryDate) {
        return ChronoUnit.DAYS.between(businessDate, expiryDate);
    }

    public boolean expired(LocalDate expiryDate) {
        return expiryDate.isBefore(businessDate);
    }

    /** {@code null} when more days are left than the notice threshold. */
    public AlertLevel level(LocalDate expiryDate) {
        long days = daysLeft(expiryDate);
        if (days < 0) {
            return AlertLevel.EXPIRED;
        }
        if (days <= critical) {
            return AlertLevel.CRITICAL;
        }
        if (days <= warning) {
            return AlertLevel.WARNING;
        }
        return days <= notice ? AlertLevel.NOTICE : null;
    }

    /** The expiry dates whose {@link #level} is {@code level}, so a list filtered by level matches the alert counts. */
    public Window window(AlertLevel level) {
        return switch (level) {
            case EXPIRED -> new Window(null, businessDate.minusDays(1));
            case CRITICAL -> new Window(businessDate, businessDate.plusDays(critical));
            case WARNING -> new Window(businessDate.plusDays(critical + 1L), businessDate.plusDays(warning));
            case NOTICE -> new Window(businessDate.plusDays(warning + 1L), businessDate.plusDays(notice));
        };
    }

    public boolean sellable(LotStatus stored, LocalDate expiryDate, BigDecimal quantityOnHand) {
        return stored == LotStatus.ACTIVE && !expired(expiryDate) && quantityOnHand.signum() > 0;
    }

    public EffectiveLotStatus effectiveStatus(LotStatus stored, LocalDate expiryDate, BigDecimal quantityOnHand) {
        return EffectiveLotStatus.of(stored, expired(expiryDate), quantityOnHand);
    }
}
