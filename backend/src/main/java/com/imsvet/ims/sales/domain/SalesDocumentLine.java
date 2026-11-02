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
 * generated columns hold the same values (ADR-016). A sold line counts what customers returned of it (ADR-130,
 * BR-RET-001); a credit-note line stores what it credits, which its amounts then are.
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

    @Column(name = "returned_quantity_base", nullable = false, precision = 14, scale = 3)
    private BigDecimal returnedQuantityBase = BigDecimal.ZERO.setScale(3);

    @Column(name = "credited_ht", updatable = false, precision = 14, scale = 2)
    private BigDecimal creditedHt;

    @Column(name = "credited_vat", updatable = false, precision = 14, scale = 2)
    private BigDecimal creditedVat;

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

    /**
     * The credit-note line of a return of {@code sold}: same product, lot, unit, species, price, discount, VAT rate and
     * cost (BR-SALE-008, BR-SALE-009); {@code quantity} in the sold unit, the amounts of ADR-130.
     */
    public static SalesDocumentLine creditFor(SalesDocumentLine sold, Long creditNoteId, BigDecimal quantity,
            BigDecimal quantityBase, BigDecimal ht, BigDecimal vat) {
        SalesDocumentLine credit = new SalesDocumentLine(creditNoteId, sold.productId, sold.lotId, sold.unitId,
                sold.targetSpeciesId, quantity, quantityBase, sold.unitPrice, sold.discountPct, sold.vatRate);
        credit.unitCost = sold.unitCost;
        credit.creditedHt = ht;
        credit.creditedVat = vat;
        return credit;
    }

    /** BR-RET-001: under the original document's lock; the database refuses more than the sold quantity. */
    public void recordReturn(BigDecimal returnedBase) {
        this.returnedQuantityBase = returnedQuantityBase.add(returnedBase);
    }

    /** The same line on the invoice of a delivery note, cost included (ADR-125). */
    public SalesDocumentLine frozenCopyFor(Long invoiceId) {
        SalesDocumentLine copy = new SalesDocumentLine(invoiceId, productId, lotId, unitId, targetSpeciesId, quantity,
                quantityBase, unitPrice, discountPct, vatRate);
        copy.unitCost = unitCost;
        return copy;
    }

    public BigDecimal lineHt() {
        return creditedHt != null ? creditedHt : SaleAmounts.lineHt(quantity, unitPrice, discountPct);
    }

    public BigDecimal lineVat() {
        return creditedVat != null ? creditedVat : SaleAmounts.lineVat(lineHt(), vatRate);
    }

    public BigDecimal lineTtc() {
        return lineHt().add(lineVat());
    }

    /** A credit-note line without discount shows none, whatever the rounding of its credited amount. */
    public BigDecimal lineDiscount() {
        if (creditedHt != null && discountPct.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return SaleAmounts.lineDiscount(quantity, unitPrice, lineHt()).max(BigDecimal.ZERO.setScale(2));
    }

    public BigDecimal returnedQuantityBase() {
        return returnedQuantityBase;
    }

    public BigDecimal creditedHt() {
        return creditedHt;
    }

    public BigDecimal creditedVat() {
        return creditedVat;
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
