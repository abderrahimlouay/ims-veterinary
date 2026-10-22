package com.imsvet.ims.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A barcode of a product (FR-PROD-003, database.md §3.2): unique and compared exactly (ADR-058). {@code unitId}
 * {@code null} is the base unit; otherwise a unit of the same product (composite foreign key, ADR-057). Barcodes
 * are added and removed, never edited.
 */
@Entity
@Table(name = "product_barcodes")
public class ProductBarcode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", updatable = false)
    private Product product;

    @Column(nullable = false, length = 64, updatable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "code_type", nullable = false, length = 10, updatable = false)
    private BarcodeType codeType;

    @Column(name = "unit_id", updatable = false)
    private Long unitId;

    protected ProductBarcode() {
    }

    /** The caller checks the format (BR-PROD-008) and that the unit belongs to the product (BR-UNIT-005). */
    public ProductBarcode(Product product, String code, BarcodeType codeType, Long unitId) {
        this.product = product;
        this.code = code;
        this.codeType = codeType;
        this.unitId = unitId;
    }

    public Long id() {
        return id;
    }

    public Product product() {
        return product;
    }

    public String code() {
        return code;
    }

    public BarcodeType codeType() {
        return codeType;
    }

    public Long unitId() {
        return unitId;
    }
}
