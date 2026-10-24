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

/** One lot returned to the supplier (BR-PURCH-009): base quantity and its {@code supplier_return} movement. */
@Entity
@Table(name = "supplier_return_lines")
public class SupplierReturnLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_id", nullable = false, updatable = false)
    private SupplierReturn supplierReturn;

    @Column(name = "lot_id", nullable = false, updatable = false)
    private Long lotId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "quantity_base", nullable = false, precision = 14, scale = 3, updatable = false)
    private BigDecimal quantityBase;

    @Column(name = "movement_id", nullable = false, updatable = false)
    private Long movementId;

    protected SupplierReturnLine() {
    }

    SupplierReturnLine(SupplierReturn supplierReturn, SupplierReturn.LineInput in) {
        this.supplierReturn = supplierReturn;
        this.lotId = in.lotId();
        this.productId = in.productId();
        this.quantityBase = in.quantityBase();
        this.movementId = in.movementId();
    }

    public Long id() {
        return id;
    }

    public Long lotId() {
        return lotId;
    }

    public Long productId() {
        return productId;
    }

    public BigDecimal quantityBase() {
        return quantityBase;
    }

    public Long movementId() {
        return movementId;
    }
}
