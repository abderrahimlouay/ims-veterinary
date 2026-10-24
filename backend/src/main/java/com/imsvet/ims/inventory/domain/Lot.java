package com.imsvet.ims.inventory.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A received batch of one product (database.md §2.2/§3.4). Identity (product, lot number, expiry) is immutable
 * (BR-LOT-011); the balance changes only through stock movements ({@link #applyDelta}, ADR-028); the cost is the
 * weighted average of what entered ({@link #enterCost}, ADR-098); the stored status changes only through {@link #apply}. The product is referenced by id: catalog tables are read through {@code ProductLookup}.
 */
@Entity
@Table(name = "lots")
public class Lot {

    /** Base quantities keep 3 decimals (database.md §2.3). */
    private static final int QUANTITY_SCALE = 3;

    /** Null-safe: also applied to an absent query parameter. */
    @Converter
    static class StatusConverter implements AttributeConverter<LotStatus, String> {

        @Override
        public String convertToDatabaseColumn(LotStatus status) {
            return status == null ? null : status.code();
        }

        @Override
        public LotStatus convertToEntityAttribute(String code) {
            return code == null ? null : LotStatus.fromCode(code);
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "lot_number", nullable = false, length = 60, updatable = false)
    private String lotNumber;

    @Column(name = "expiry_date", nullable = false, updatable = false)
    private LocalDate expiryDate;

    @Column(name = "unit_cost", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "quantity_on_hand", nullable = false, precision = 14, scale = 3)
    private BigDecimal quantityOnHand;

    @Convert(converter = StatusConverter.class)
    @Column(nullable = false, length = 30)
    private LotStatus status;

    @Column(name = "blocked_reason", columnDefinition = "TEXT")
    private String blockedReason;

    @Column(name = "blocked_by")
    private Long blockedBy;

    @Column(name = "blocked_at")
    private Instant blockedAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "supplier_id")
    private Long supplierId;

    protected Lot() {
    }

    /**
     * Moves the balance by a movement's quantity (ADR-028). Only {@code StockService} calls it, after locking the lot
     * and checking that the balance stays ≥ 0 (BR-STOCK-001/002; architecture test); the CHECK is the safety net.
     */
    public void applyDelta(BigDecimal delta) {
        this.quantityOnHand = quantityOnHand.add(delta).setScale(QUANTITY_SCALE);
    }

    /**
     * Applies a stored-status transition (BR-LOT-009, ADR-060); the caller locks the row, checks the permission and
     * audits first. Block and recall record reason, user and time; unblock clears them (the reason is audited).
     */
    public void apply(LotStatus.Action action, String reason, Long userId, Instant now) {
        this.status = status.after(action);
        if (action == LotStatus.Action.UNBLOCK) {
            this.blockedReason = null;
            this.blockedBy = null;
            this.blockedAt = null;
        } else {
            this.blockedReason = reason;
            this.blockedBy = userId;
            this.blockedAt = now;
        }
    }

    /**
     * Stock entering the lot (receipt or opening stock): the cost becomes the weighted average on the current
     * balance ({@link LotCost}, ADR-098). Called under the lot lock, before the entry's movement is posted.
     */
    public void enterCost(BigDecimal quantity, BigDecimal entryCost) {
        this.unitCost = LotCost.weightedAverage(quantityOnHand, unitCost, quantity, entryCost);
    }

    /** Set by the first receipt and kept (ADR-105); opening-stock and found lots have none. */
    public void assignSupplier(Long supplier) {
        if (this.supplierId == null) {
            this.supplierId = supplier;
        }
    }

    public Long id() {
        return id;
    }

    public Long productId() {
        return productId;
    }

    public String lotNumber() {
        return lotNumber;
    }

    public LocalDate expiryDate() {
        return expiryDate;
    }

    public BigDecimal unitCost() {
        return unitCost;
    }

    public BigDecimal quantityOnHand() {
        return quantityOnHand;
    }

    public LotStatus status() {
        return status;
    }

    public String blockedReason() {
        return blockedReason;
    }

    public Long blockedBy() {
        return blockedBy;
    }

    public Instant blockedAt() {
        return blockedAt;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public Long supplierId() {
        return supplierId;
    }
}
