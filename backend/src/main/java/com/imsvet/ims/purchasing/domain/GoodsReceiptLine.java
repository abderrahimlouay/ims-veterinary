package com.imsvet.ims.purchasing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * One received lot (BR-PURCH-005): base quantity, cost per base unit (ADR-098), conformity (ADR-095), its
 * {@code purchase_receipt} movement, and the lot's state before the receipt for a cancellation (ADR-106, SD-131).
 */
@Entity
@Table(name = "goods_receipt_lines")
public class GoodsReceiptLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false, updatable = false)
    private GoodsReceipt receipt;

    @Column(name = "po_line_id", updatable = false)
    private Long poLineId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "lot_id", nullable = false, updatable = false)
    private Long lotId;

    @Column(name = "quantity_base", nullable = false, precision = 14, scale = 3, updatable = false)
    private BigDecimal quantityBase;

    @Column(name = "unit_cost", nullable = false, precision = 14, scale = 4, updatable = false)
    private BigDecimal unitCost;

    @Column(name = "is_conforming", nullable = false, updatable = false)
    private boolean conforming;

    @Column(name = "movement_id", nullable = false, updatable = false)
    private Long movementId;

    @Column(name = "lot_created", nullable = false, updatable = false)
    private boolean lotCreated;

    @Column(name = "lot_cost_before", precision = 14, scale = 4, updatable = false)
    private BigDecimal lotCostBefore;

    @Column(name = "lot_status_before", length = 30, updatable = false)
    private String lotStatusBefore;

    @Column(name = "lot_blocked", nullable = false, updatable = false)
    private boolean lotBlocked;

    protected GoodsReceiptLine() {
    }

    GoodsReceiptLine(GoodsReceipt receipt, GoodsReceipt.LineInput in) {
        this.receipt = receipt;
        this.poLineId = in.poLineId();
        this.productId = in.productId();
        this.lotId = in.lotId();
        this.quantityBase = in.quantityBase();
        this.unitCost = in.unitCost();
        this.conforming = in.conforming();
        this.movementId = in.movementId();
        this.lotCreated = in.lotCreated();
        this.lotCostBefore = in.lotCostBefore();
        this.lotStatusBefore = in.lotStatusBefore();
        this.lotBlocked = in.lotBlocked();
    }

    public Long id() {
        return id;
    }

    public Long poLineId() {
        return poLineId;
    }

    public Long productId() {
        return productId;
    }

    public Long lotId() {
        return lotId;
    }

    public BigDecimal quantityBase() {
        return quantityBase;
    }

    public BigDecimal unitCost() {
        return unitCost;
    }

    public boolean conforming() {
        return conforming;
    }

    public Long movementId() {
        return movementId;
    }

    public boolean lotCreated() {
        return lotCreated;
    }

    public BigDecimal lotCostBefore() {
        return lotCostBefore;
    }

    public String lotStatusBefore() {
        return lotStatusBefore;
    }

    public boolean lotBlocked() {
        return lotBlocked;
    }
}
