package com.imsvet.ims.sales.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A customer category and its default discount (FR-SALE-005, ADR-112): renamed, never deleted; the discount is the
 * entitlement of its customers on products without an own price (ADR-107, ADR-113).
 */
@Entity
@Table(name = "customer_categories")
public class CustomerCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "discount_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPct;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected CustomerCategory() {
    }

    public CustomerCategory(String name, BigDecimal discountPct, Instant now) {
        this.name = name;
        this.discountPct = discountPct;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String name, BigDecimal discountPct, Instant now) {
        this.name = name;
        this.discountPct = discountPct;
        this.updatedAt = now;
    }

    public Integer id() {
        return id;
    }

    public String name() {
        return name;
    }

    public BigDecimal discountPct() {
        return discountPct;
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
