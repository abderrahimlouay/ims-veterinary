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
import java.math.RoundingMode;

/**
 * A packaging unit of a product (FR-PROD-006/007, database.md §2.3): {@code factorToBase} base units in one of
 * this unit. The base unit itself has no row. {@code salePrice} {@code null} means derived from the product's
 * price per base unit (ADR-063).
 */
@Entity
@Table(name = "product_units")
public class ProductUnit {

    /** Prices per base unit keep 4 decimals (ADR-049); the derived unit price is shown with the same scale. */
    private static final int PRICE_SCALE = 4;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", updatable = false)
    private Product product;

    @Column(nullable = false, length = 40)
    private String name;

    @Column(name = "factor_to_base", nullable = false, precision = 14, scale = 4)
    private BigDecimal factorToBase;

    @Column(name = "sale_price", precision = 14, scale = 2)
    private BigDecimal salePrice;

    @Column(name = "is_sellable", nullable = false)
    private boolean sellable;

    @Version
    private long version;

    protected ProductUnit() {
    }

    /** The caller validates the values and checks {@code price.edit} for a price (BR-UNIT-001, BR-PROD-006). */
    public ProductUnit(Product product, String name, BigDecimal factorToBase, BigDecimal salePrice, boolean sellable) {
        this.product = product;
        this.name = name;
        this.factorToBase = factorToBase;
        this.salePrice = salePrice;
        this.sellable = sellable;
    }

    /** The caller refuses a factor change on a unit in use (BR-UNIT-004). */
    public void define(String newName, BigDecimal newFactorToBase, boolean newSellable) {
        this.name = newName;
        this.factorToBase = newFactorToBase;
        this.sellable = newSellable;
    }

    /** The caller checks {@code price.edit} and audits the change (BR-PROD-006). */
    public void setSalePrice(BigDecimal newSalePrice) {
        this.salePrice = newSalePrice;
    }

    /** ADR-063: the stored price, else {@code factor × product sale price}, 4 decimals half-up (informational). */
    public BigDecimal effectiveSalePrice() {
        BigDecimal price = salePrice != null ? salePrice : factorToBase.multiply(product.salePrice());
        return price.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public BigDecimal factorToBase() {
        return factorToBase;
    }

    public BigDecimal salePrice() {
        return salePrice;
    }

    public boolean sellable() {
        return sellable;
    }

    public long version() {
        return version;
    }
}
