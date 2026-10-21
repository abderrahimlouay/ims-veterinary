package com.imsvet.ims.catalog.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hibernate.annotations.OptimisticLock;

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

    /** Edited on its own tab: excluded from the optimistic lock so it causes no false conflict there. */
    @ElementCollection
    @CollectionTable(name = "product_active_ingredients", joinColumns = @JoinColumn(name = "product_id"))
    @OptimisticLock(excluded = true)
    private List<ProductIngredient> ingredients = new ArrayList<>();

    /** Target species (ADR-047); edited on its own tab, excluded from the optimistic lock. */
    @ManyToMany
    @JoinTable(name = "product_target_species", joinColumns = @JoinColumn(name = "product_id"),
            inverseJoinColumns = @JoinColumn(name = "species_id"))
    @OptimisticLock(excluded = true)
    private Set<AnimalSpecies> targetSpecies = new HashSet<>();

    /** Withdrawal periods per species (FR-PROD-005); edited on its own tab, excluded from the optimistic lock. */
    @ElementCollection
    @CollectionTable(name = "product_withdrawal_periods", joinColumns = @JoinColumn(name = "product_id"))
    @OptimisticLock(excluded = true)
    private List<WithdrawalPeriod> withdrawalPeriods = new ArrayList<>();

    protected Product() {
    }

    public Product(Core core, Instant now) {
        apply(core, now);
        this.createdAt = now;
    }

    public void update(Core core, Instant now) {
        apply(core, now);
    }

    /** Prices per base unit (ADR-049); the caller checks {@code price.edit} and audits (BR-PROD-006). */
    public void setPrices(BigDecimal newPurchasePrice, BigDecimal newSalePrice, Instant now) {
        this.purchasePrice = newPurchasePrice;
        this.salePrice = newSalePrice;
        this.updatedAt = now;
    }

    /** Replaces the whole ingredient set (FR-PROD-004). */
    public void replaceIngredients(List<ProductIngredient> newIngredients) {
        ingredients.clear();
        ingredients.addAll(newIngredients);
    }

    public List<ProductIngredient> ingredients() {
        return List.copyOf(ingredients);
    }

    public void replaceTargetSpecies(Set<AnimalSpecies> species) {
        targetSpecies.clear();
        targetSpecies.addAll(species);
    }

    public Set<AnimalSpecies> targetSpecies() {
        return Set.copyOf(targetSpecies);
    }

    /** BR-PROD-007: at most one period per species; the caller checks it. */
    public void replaceWithdrawalPeriods(List<WithdrawalPeriod> periods) {
        withdrawalPeriods.clear();
        withdrawalPeriods.addAll(periods);
    }

    public List<WithdrawalPeriod> withdrawalPeriods() {
        return List.copyOf(withdrawalPeriods);
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
