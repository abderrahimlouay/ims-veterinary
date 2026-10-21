package com.imsvet.ims.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A product sheet (FR-PROD-001/002/009, database.md §3.2). Archived products stay readable and editable;
 * only new documents refuse them (BR-PROD-004, from Phase 5). Prices are per base unit (ADR-049).
 */
@Entity
@Table(name = "products")
public class Product {

    /** The descriptive fields set at creation and by an update, prices excluded (BR-PROD-006). */
    public record Core(String sku, String commercialName, TherapeuticCategory category, DosageForm dosageForm,
            Manufacturer manufacturer, String baseUnit, boolean divisible, BigDecimal vatRate, BigDecimal minStock,
            BigDecimal maxStock, BigDecimal reorderPoint) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String sku;

    @Column(name = "commercial_name", nullable = false, length = 200)
    private String commercialName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private TherapeuticCategory category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dosage_form_id")
    private DosageForm dosageForm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manufacturer_id")
    private Manufacturer manufacturer;

    @Column(name = "base_unit", nullable = false, length = 20)
    private String baseUnit;

    @Column(name = "is_divisible", nullable = false)
    private boolean divisible;

    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal vatRate;

    @Column(name = "purchase_price", nullable = false, precision = 14, scale = 4)
    private BigDecimal purchasePrice = BigDecimal.ZERO.setScale(4);

    @Column(name = "sale_price", nullable = false, precision = 14, scale = 4)
    private BigDecimal salePrice = BigDecimal.ZERO.setScale(4);

    @Column(name = "min_stock", nullable = false, precision = 14, scale = 3)
    private BigDecimal minStock;

    @Column(name = "max_stock", precision = 14, scale = 3)
    private BigDecimal maxStock;

    @Column(name = "reorder_point", nullable = false, precision = 14, scale = 3)
    private BigDecimal reorderPoint;

    @Column(name = "is_archived", nullable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Product() {
    }

    public Product(Core core, Instant now) {
        apply(core, now);
        this.createdAt = now;
    }

    public void update(Core core, Instant now) {
        apply(core, now);
    }

    public void archive(Instant now) {
        this.archived = true;
        this.updatedAt = now;
    }

    public void restore(Instant now) {
        this.archived = false;
        this.updatedAt = now;
    }

    private void apply(Core core, Instant now) {
        this.sku = core.sku();
        this.commercialName = core.commercialName();
        this.category = core.category();
        this.dosageForm = core.dosageForm();
        this.manufacturer = core.manufacturer();
        this.baseUnit = core.baseUnit();
        this.divisible = core.divisible();
        this.vatRate = core.vatRate();
        this.minStock = core.minStock();
        this.maxStock = core.maxStock();
        this.reorderPoint = core.reorderPoint();
        this.updatedAt = now;
    }

    public Long id() {
        return id;
    }

    public String sku() {
        return sku;
    }

    public String commercialName() {
        return commercialName;
    }

    public TherapeuticCategory category() {
        return category;
    }

    public DosageForm dosageForm() {
        return dosageForm;
    }

    public Manufacturer manufacturer() {
        return manufacturer;
    }

    public String baseUnit() {
        return baseUnit;
    }

    public boolean divisible() {
        return divisible;
    }

    public BigDecimal vatRate() {
        return vatRate;
    }

    public BigDecimal purchasePrice() {
        return purchasePrice;
    }

    public BigDecimal salePrice() {
        return salePrice;
    }

    public BigDecimal minStock() {
        return minStock;
    }

    public BigDecimal maxStock() {
        return maxStock;
    }

    public BigDecimal reorderPoint() {
        return reorderPoint;
    }

    public boolean archived() {
        return archived;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
