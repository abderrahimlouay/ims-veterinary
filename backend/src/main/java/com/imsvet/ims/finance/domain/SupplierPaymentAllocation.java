package com.imsvet.ims.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** What a supplier payment settles on an invoice (ADR-132): never changed or removed; stops counting once reversed. */
@Entity
@Table(name = "supplier_payment_allocations")
public class SupplierPaymentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private Long paymentId;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private Long invoiceId;

    @Column(nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    protected SupplierPaymentAllocation() {
    }

    public SupplierPaymentAllocation(Long paymentId, Long invoiceId, BigDecimal amount, Long createdBy, Instant now) {
        this.paymentId = paymentId;
        this.invoiceId = invoiceId;
        this.amount = amount;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    public Long id() {
        return id;
    }

    public Long paymentId() {
        return paymentId;
    }

    public Long invoiceId() {
        return invoiceId;
    }

    public BigDecimal amount() {
        return amount;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
