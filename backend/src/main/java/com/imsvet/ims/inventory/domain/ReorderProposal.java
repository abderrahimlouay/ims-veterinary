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
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A reorder proposal (W10, BR-STOCK-010): at most one pending per product (unique generated column), updated in
 * place by each generation; dismissing suppresses new proposals for the product until a generation sees its
 * sellable stock above the reorder point (ADR-079); converting names the supplier of the new draft order (ADR-102).
 */
@Entity
@Table(name = "reorder_proposals")
public class ReorderProposal {

    /** Null-safe: also applied to an absent query parameter. */
    @Converter
    static class StatusConverter implements AttributeConverter<ProposalStatus, String> {

        @Override
        public String convertToDatabaseColumn(ProposalStatus status) {
            return status == null ? null : status.code();
        }

        @Override
        public ProposalStatus convertToEntityAttribute(String code) {
            return code == null ? null : ProposalStatus.fromCode(code).orElseThrow();
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "current_qty", nullable = false, precision = 14, scale = 3)
    private BigDecimal currentQty;

    @Column(name = "suggested_qty", nullable = false, precision = 14, scale = 3)
    private BigDecimal suggestedQty;

    @Convert(converter = StatusConverter.class)
    @Column(nullable = false, length = 15)
    private ProposalStatus status;

    @Column(nullable = false)
    private boolean suppressed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "dismissed_at")
    private Instant dismissedAt;

    @Column(name = "dismissed_by")
    private Long dismissedBy;

    @Column(name = "supplier_id")
    private Long supplierId;

    @Version
    private long version;

    protected ReorderProposal() {
    }

    public ReorderProposal(Long productId, BigDecimal currentQty, BigDecimal suggestedQty, Instant now) {
        this.productId = productId;
        this.currentQty = currentQty;
        this.suggestedQty = suggestedQty;
        this.status = ProposalStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void refresh(BigDecimal current, BigDecimal suggested, Instant now) {
        this.currentQty = current;
        this.suggestedQty = suggested;
        this.updatedAt = now;
    }

    /** Only a pending proposal; the caller checks the status first. */
    public void dismiss(Long userId, Instant now) {
        if (status != ProposalStatus.PENDING) {
            throw new IllegalStateException("Proposal " + id + " is " + status.code());
        }
        this.status = ProposalStatus.DISMISSED;
        this.suppressed = true;
        this.dismissedBy = userId;
        this.dismissedAt = now;
        this.updatedAt = now;
    }

    /** Converted into a draft purchase order for this supplier, once (BR-PURCH-010); the caller checks the status. */
    public void convert(Long supplier, Instant now) {
        if (status != ProposalStatus.PENDING) {
            throw new IllegalStateException("Proposal " + id + " is " + status.code());
        }
        this.status = ProposalStatus.CONVERTED;
        this.supplierId = supplier;
        this.updatedAt = now;
    }

    /** The product's sellable stock went above the reorder point (ADR-079). */
    public void liftSuppression(Instant now) {
        this.suppressed = false;
        this.updatedAt = now;
    }

    public Long id() {
        return id;
    }

    public Long productId() {
        return productId;
    }

    public BigDecimal currentQty() {
        return currentQty;
    }

    public BigDecimal suggestedQty() {
        return suggestedQty;
    }

    public ProposalStatus status() {
        return status;
    }

    public boolean suppressed() {
        return suppressed;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant dismissedAt() {
        return dismissedAt;
    }

    public Long dismissedBy() {
        return dismissedBy;
    }

    public Long supplierId() {
        return supplierId;
    }
}
