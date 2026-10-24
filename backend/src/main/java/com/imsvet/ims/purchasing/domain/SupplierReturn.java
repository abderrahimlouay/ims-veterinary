package com.imsvet.ims.purchasing.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A return of lots to a supplier (FR-PURCH-006, W4, ADR-101): numbered SR (ADR-097), with its reason; each line a
 * lot, its base quantity and its {@code supplier_return} movement. Stock only in Phase 5; never edited.
 */
@Entity
@Table(name = "supplier_returns")
public class SupplierReturn {

    /** One returned lot, once its movement is posted. */
    public record LineInput(long lotId, long productId, BigDecimal quantityBase, long movementId) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30, updatable = false)
    private String number;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private Long supplierId;

    @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
    private String reason;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "supplierReturn", cascade = CascadeType.ALL)
    @OrderBy("id")
    private List<SupplierReturnLine> lines = new ArrayList<>();

    protected SupplierReturn() {
    }

    public SupplierReturn(String number, Long supplierId, String reason, Long createdBy, Instant createdAt) {
        this.number = number;
        this.supplierId = supplierId;
        this.reason = reason;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public void addLines(List<LineInput> inputs) {
        for (LineInput in : inputs) {
            lines.add(new SupplierReturnLine(this, in));
        }
    }

    public Long id() {
        return id;
    }

    public String number() {
        return number;
    }

    public Long supplierId() {
        return supplierId;
    }

    public String reason() {
        return reason;
    }

    public Long createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<SupplierReturnLine> lines() {
        return List.copyOf(lines);
    }
}
