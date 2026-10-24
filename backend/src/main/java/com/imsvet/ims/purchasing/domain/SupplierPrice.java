package com.imsvet.ims.purchasing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One entry of a supplier's price history for a product (FR-PURCH-002, BR-PURCH-008): price per base unit with
 * 4 decimals (ADR-049, ADR-094), discount 0–100. Never edited.
 */
@Entity
@Table(name = "supplier_price_history")
public class SupplierPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private Long supplierId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 4, updatable = false)
    private BigDecimal unitPrice;

    @Column(name = "discount_pct", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal discountPct;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private LocalDate validFrom;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    protected SupplierPrice() {
    }

    public SupplierPrice(Long supplierId, Long productId, BigDecimal unitPrice, BigDecimal discountPct,
            LocalDate validFrom, Long createdBy, Instant createdAt) {
        this.supplierId = supplierId;
        this.productId = productId;
        this.unitPrice = unitPrice;
        this.discountPct = discountPct;
        this.validFrom = validFrom;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public Long id() {
        return id;
    }

    public Long supplierId() {
        return supplierId;
    }

    public Long productId() {
        return productId;
    }

    public BigDecimal unitPrice() {
        return unitPrice;
    }

    public BigDecimal discountPct() {
        return discountPct;
    }

    public LocalDate validFrom() {
        return validFrom;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Long createdBy() {
        return createdBy;
    }
}
