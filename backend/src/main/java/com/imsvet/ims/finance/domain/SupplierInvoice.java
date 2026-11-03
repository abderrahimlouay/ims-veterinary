package com.imsvet.ims.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A supplier's invoice or credit note, as the supplier issued it (ADR-133, BR-PAY-008/011). Never edited: the only
 * change is its cancellation, with the reason, when nothing settles it (BR-PAY-015).
 */
@Entity
@Table(name = "supplier_invoices")
public class SupplierInvoice {

    public static final String RECORDED = "recorded";
    public static final String CANCELLED = "cancelled";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private Long supplierId;

    @Column(name = "invoice_number", nullable = false, updatable = false, length = 60)
    private String invoiceNumber;

    @Column(name = "receipt_id", updatable = false)
    private Long receiptId;

    @Column(name = "supplier_return_id", updatable = false)
    private Long supplierReturnId;

    @Column(name = "invoice_date", nullable = false, updatable = false)
    private LocalDate invoiceDate;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Column(name = "total_ttc", nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal totalTtc;

    @Column(name = "is_credit_note", nullable = false, updatable = false)
    private boolean creditNote;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SupplierInvoice() {
    }

    public SupplierInvoice(Long supplierId, String invoiceNumber, Long receiptId, Long supplierReturnId,
            LocalDate invoiceDate, LocalDate dueDate, BigDecimal totalTtc, boolean creditNote, Long createdBy,
            Instant now) {
        this.supplierId = supplierId;
        this.invoiceNumber = invoiceNumber;
        this.receiptId = receiptId;
        this.supplierReturnId = supplierReturnId;
        this.invoiceDate = invoiceDate;
        this.dueDate = dueDate;
        this.totalTtc = totalTtc;
        this.creditNote = creditNote;
        this.status = RECORDED;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    public void cancel(String reason, Long by, Instant now) {
        this.status = CANCELLED;
        this.cancelReason = reason;
        this.cancelledBy = by;
        this.cancelledAt = now;
    }

    public boolean recorded() {
        return RECORDED.equals(status);
    }

    public Long id() {
        return id;
    }

    public Long supplierId() {
        return supplierId;
    }

    public String invoiceNumber() {
        return invoiceNumber;
    }

    public Long receiptId() {
        return receiptId;
    }

    public Long supplierReturnId() {
        return supplierReturnId;
    }

    public LocalDate invoiceDate() {
        return invoiceDate;
    }

    public LocalDate dueDate() {
        return dueDate;
    }

    public BigDecimal totalTtc() {
        return totalTtc;
    }

    public boolean creditNote() {
        return creditNote;
    }

    public String status() {
        return status;
    }

    public String cancelReason() {
        return cancelReason;
    }

    public Long cancelledBy() {
        return cancelledBy;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }

    public Long createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
