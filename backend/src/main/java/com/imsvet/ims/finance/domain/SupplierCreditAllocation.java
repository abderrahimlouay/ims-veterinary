package com.imsvet.ims.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** What a supplier credit note settles on an invoice of the same supplier (ADR-133): never changed or removed. */
@Entity
@Table(name = "supplier_credit_allocations")
public class SupplierCreditAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "credit_note_id", nullable = false, updatable = false)
    private Long creditNoteId;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private Long invoiceId;

    @Column(nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    protected SupplierCreditAllocation() {
    }

    public SupplierCreditAllocation(Long creditNoteId, Long invoiceId, BigDecimal amount, Long createdBy, Instant now) {
        this.creditNoteId = creditNoteId;
        this.invoiceId = invoiceId;
        this.amount = amount;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    public Long id() {
        return id;
    }

    public Long creditNoteId() {
        return creditNoteId;
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
