package com.imsvet.ims.sales.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * A line of a sales document: one lot (BR-LOT-006), {@code null} on a quote; the price per sold unit (ADR-123) and the
 * VAT rate as they were when the line was added (BR-SALE-008). Amounts are computed, never stored from a client; the
 * generated columns hold the same values (ADR-016).
 */
@Entity
@Table(name = "sales_document_lines")
public class SalesDocumentLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false, updatable = false)
    private Long documentId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "lot_id")
    private Long lotId;

    @Column(name = "unit_id", updatable = false)
    private Long unitId;

    @Column(name = "target_species_id", updatable = false)
    private Integer targetSpeciesId;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    @Column(name = "quantity_base", nullable = false, precision = 14, scale = 3)
    private BigDecimal quantityBase;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "unit_cost", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitCost = BigDecimal.ZERO.setScale(4);

    @Column(name = "discount_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPct;

    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal vatRate;

    protected SalesDocumentLine() {
    }

    public SalesDocumentLine(Long documentId, Long productId, Long lotId, Long unitId, Integer targetSpeciesId,
            BigDecimal quantity, BigDecimal quantityBase, BigDecimal unitPrice, BigDecimal discountPct,
            BigDecimal vatRate) {
        this.documentId = documentId;
        this.productId = productId;
        this.lotId = lotId;
        this.unitId = unitId;
        this.targetSpeciesId = targetSpeciesId;
        this.quantity = quantity;
        this.quantityBase = quantityBase;
        this.unitPrice = unitPrice;
        this.discountPct = discountPct;
        this.vatRate = vatRate;
    }

    /** BR-SALE-009: the lot's cost at confirmation, for margin reporting. */
    public void snapshotCost(BigDecimal lotCost) {
        this.unitCost = lotCost;
    }

    /** The same line on the invoice of a delivery note, cost included (ADR-125). */
    public SalesDocumentLine frozenCopyFor(Long invoiceId) {
        SalesDocumentLine copy = new SalesDocumentLine(invoiceId, productId, lotId, unitId, targetSpeciesId, quantity,
                quantityBase, unitPrice, discountPct, vatRate);
        copy.unitCost = unitCost;
        return copy;
    }

    public BigDecimal lineHt() {
        return SaleAmounts.lineHt(quantity, unitPrice, discountPct);
    }

    public BigDecimal lineVat() {
        return SaleAmounts.lineVat(lineHt(), vatRate);
    }

    public BigDecimal lineTtc() {
        return lineHt().add(lineVat());
    }

    public BigDecimal lineDiscount() {
        return SaleAmounts.lineDiscount(quantity, unitPrice, lineHt());
    }

    public Long id() {
        return id;
    }

    public Long documentId() {
        return documentId;
    }

    public Long productId() {
        return productId;
    }

    public Long lotId() {
        return lotId;
    }

    public Long unitId() {
        return unitId;
    }

    public Integer targetSpeciesId() {
        return targetSpeciesId;
    }

    public BigDecimal quantity() {
        return quantity;
    }

    public BigDecimal quantityBase() {
        return quantityBase;
    }

    public BigDecimal unitPrice() {
        return unitPrice;
    }

    public BigDecimal unitCost() {
        return unitCost;
    }

    public BigDecimal discountPct() {
        return discountPct;
    }

    public BigDecimal vatRate() {
        return vatRate;
    }
}
