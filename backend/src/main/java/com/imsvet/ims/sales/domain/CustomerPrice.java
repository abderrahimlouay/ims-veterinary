package com.imsvet.ims.sales.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/** A customer's own price for a product, per base unit (FR-SALE-010, ADR-111); its history is the audit log. */
@Entity
@Table(name = "customer_product_prices")
public class CustomerPrice {

    @Embeddable
    public record Key(@Column(name = "customer_id") Long customerId, @Column(name = "product_id") Long productId)
            implements Serializable {
    }

    @EmbeddedId
    private Key id;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected CustomerPrice() {
    }

    public CustomerPrice(long customerId, long productId, BigDecimal unitPrice, Instant now) {
        this.id = new Key(customerId, productId);
        this.unitPrice = unitPrice;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void change(BigDecimal price, Instant now) {
        this.unitPrice = price;
        this.updatedAt = now;
    }

    public long customerId() {
        return id.customerId();
    }

    public long productId() {
        return id.productId();
    }

    public BigDecimal unitPrice() {
        return unitPrice;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
