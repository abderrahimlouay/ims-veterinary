package com.imsvet.ims.sales.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A sales document (FR-SALE-001/002). Only a draft changes (BR-SALE-004); it has no number until confirmation
 * (ADR-122) and an abandoned draft stays cancelled without one. Totals are computed by the server (BR-SALE-008).
 */
@Entity
@Table(name = "sales_documents")
public class SalesDocument {

    @Converter
    static class TypeConverter implements AttributeConverter<SalesDocType, String> {
        @Override
        public String convertToDatabaseColumn(SalesDocType type) {
            return type == null ? null : type.code();
        }

        @Override
        public SalesDocType convertToEntityAttribute(String code) {
            return SalesDocType.fromCode(code).orElseThrow();
        }
    }

    @Converter
    static class StatusConverter implements AttributeConverter<SalesDocStatus, String> {
        @Override
        public String convertToDatabaseColumn(SalesDocStatus status) {
            return status == null ? null : status.code();
        }

        @Override
        public SalesDocStatus convertToEntityAttribute(String code) {
            return SalesDocStatus.fromCode(code).orElseThrow();
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Convert(converter = TypeConverter.class)
    @Column(name = "doc_type", nullable = false, updatable = false, length = 30)
    private SalesDocType docType;

    @Column(length = 30)
    private String number;

    @Convert(converter = StatusConverter.class)
    @Column(nullable = false, length = 30)
    private SalesDocStatus status = SalesDocStatus.DRAFT;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "parent_id", updatable = false)
    private Long parentId;

    @Column(name = "is_credit_sale", nullable = false)
    private boolean creditSale;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "cash_session_id")
    private Long cashSessionId;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "total_ht", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalHt = BigDecimal.ZERO.setScale(2);

    @Column(name = "total_discount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalDiscount = BigDecimal.ZERO.setScale(2);

    @Column(name = "total_vat", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalVat = BigDecimal.ZERO.setScale(2);

    @Column(name = "total_ttc", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalTtc = BigDecimal.ZERO.setScale(2);

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    private long version;

    protected SalesDocument() {
    }

    public SalesDocument(SalesDocType docType, Long customerId, boolean creditSale, LocalDate dueDate, String notes,
            Long createdBy, Instant now) {
        this.docType = docType;
        this.customerId = customerId;
        this.creditSale = creditSale;
        this.dueDate = dueDate;
        this.notes = notes;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updateHeader(Long customerId, boolean creditSale, LocalDate dueDate, String notes, Instant now) {
        this.customerId = customerId;
        this.creditSale = creditSale;
        this.dueDate = dueDate;
        this.notes = notes;
        this.updatedAt = now;
    }

    /** BR-SALE-008: from the server's rounded lines. */
    public void applyTotals(BigDecimal ht, BigDecimal discount, BigDecimal vat, BigDecimal ttc, Instant now) {
        this.totalHt = ht;
        this.totalDiscount = discount;
        this.totalVat = vat;
        this.totalTtc = ttc;
        this.updatedAt = now;
    }

    /** Numbered and issued here (ADR-122); {@code cashSessionId} {@code null} without a cash part (ADR-118). */
    public void confirm(String number, Long cashSessionId, Instant now) {
        this.number = number;
        this.cashSessionId = cashSessionId;
        this.status = SalesDocStatus.CONFIRMED;
        this.issuedAt = now;
        this.updatedAt = now;
    }

    public void abandon(Long by, Instant now) {
        this.status = SalesDocStatus.CANCELLED;
        this.cancelledBy = by;
        this.cancelledAt = now;
        this.updatedAt = now;
    }

    public Long id() {
        return id;
    }

    public SalesDocType docType() {
        return docType;
    }

    public String number() {
        return number;
    }

    public SalesDocStatus status() {
        return status;
    }

    public Long customerId() {
        return customerId;
    }

    public Long parentId() {
        return parentId;
    }

    public boolean creditSale() {
        return creditSale;
    }

    public LocalDate dueDate() {
        return dueDate;
    }

    public Long cashSessionId() {
        return cashSessionId;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public BigDecimal totalHt() {
        return totalHt;
    }

    public BigDecimal totalDiscount() {
        return totalDiscount;
    }

    public BigDecimal totalVat() {
        return totalVat;
    }

    public BigDecimal totalTtc() {
        return totalTtc;
    }

    public String notes() {
        return notes;
    }

    public Long createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }

    public long version() {
        return version;
    }
}
