package com.imsvet.ims.inventory.application;

import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.inventory.domain.AlertLevel;
import com.imsvet.ims.inventory.domain.ExpiryPolicy;
import com.imsvet.ims.inventory.domain.Lot;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Read models of lots (api.md §4.4). {@code status} is the effective status and {@code alertLevel} the level of the
 * current policy, or {@code null}; both are computed on each read (ADR-056, ADR-059).
 */
public final class LotViews {

    private LotViews() {
    }

    public record LotProduct(long id, String sku, String commercialName, String baseUnit) {
    }

    public record LotSummary(long id, LotProduct product, String lotNumber, LocalDate expiryDate,
            BigDecimal quantityOnHand, String status, String alertLevel, long daysLeft) {
    }

    public record LotDetail(long id, LotProduct product, String lotNumber, LocalDate expiryDate,
            BigDecimal quantityOnHand, String status, String alertLevel, long daysLeft, BigDecimal unitCost,
            String blockedReason, Long blockedBy, Instant blockedAt, Instant receivedAt, boolean sellable) {
    }

    static LotSummary summary(Lot l, ProductRef product, ExpiryPolicy policy) {
        return new LotSummary(l.id(), product(product), l.lotNumber(), l.expiryDate(), l.quantityOnHand(),
                status(l, policy), level(l, policy), policy.daysLeft(l.expiryDate()));
    }

    static LotDetail detail(Lot l, ProductRef product, ExpiryPolicy policy) {
        return new LotDetail(l.id(), product(product), l.lotNumber(), l.expiryDate(), l.quantityOnHand(),
                status(l, policy), level(l, policy), policy.daysLeft(l.expiryDate()), l.unitCost(), l.blockedReason(),
                l.blockedBy(), l.blockedAt(), l.receivedAt(), policy.sellable(l.status(), l.expiryDate(),
                        l.quantityOnHand()));
    }

    private static LotProduct product(ProductRef p) {
        return new LotProduct(p.id(), p.sku(), p.commercialName(), p.baseUnit());
    }

    private static String status(Lot l, ExpiryPolicy policy) {
        return policy.effectiveStatus(l.status(), l.expiryDate(), l.quantityOnHand()).code();
    }

    private static String level(Lot l, ExpiryPolicy policy) {
        AlertLevel level = policy.level(l.expiryDate());
        return level == null ? null : level.code();
    }
}
