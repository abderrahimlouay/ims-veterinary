package com.imsvet.ims.inventory.application;

import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.inventory.domain.AlertLevel;
import com.imsvet.ims.inventory.domain.ExpiryPolicy;
import com.imsvet.ims.inventory.domain.Lot;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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

    /** api.md §5.3: one proposed lot; {@code available} is its whole stock on hand. */
    public record FefoLine(long lotId, String lotNumber, LocalDate expiryDate, BigDecimal quantityBase,
            BigDecimal available, String alertLevel) {
    }

    public record FefoPreviewView(BigDecimal requestedBase, List<FefoLine> allocations, BigDecimal shortfallBase) {
    }

    public record Thresholds(int notice, int warning, int critical) {
    }

    /** api.md §4.4: lots with stock by alert level, with the thresholds and business date used (W12). */
    public record ExpiryAlerts(List<LotSummary> expired, List<LotSummary> critical, List<LotSummary> warning,
            List<LotSummary> notice, Thresholds thresholds, LocalDate businessDate) {
    }

    /** api.md §5.7: the recalled lot with who recalled it and when. */
    public record RecalledLot(long id, String status, Instant blockedAt, Long blockedBy) {
    }

    /** A sale of the lot to a known customer; listed from Phase 7 (ADR-068). */
    public record RecallBuyer(long customerId, String customerName, String phone, String documentNumber,
            String documentType, Instant issuedAt, BigDecimal quantityBase, String unit) {
    }

    /** A counter sale without customer; listed from Phase 7 (ADR-068). */
    public record AnonymousSale(String documentNumber, Instant issuedAt, BigDecimal quantityBase) {
    }

    public record RecallReport(RecalledLot lot, List<RecallBuyer> buyers, List<AnonymousSale> anonymousSales,
            BigDecimal remainingStockBase) {
    }

    /** Until sales exist (Phase 7) both buyer lists are empty (ADR-068). */
    static RecallReport recallReport(Lot l) {
        return new RecallReport(new RecalledLot(l.id(), l.status().code(), l.blockedAt(), l.blockedBy()), List.of(),
                List.of(), l.quantityOnHand());
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
