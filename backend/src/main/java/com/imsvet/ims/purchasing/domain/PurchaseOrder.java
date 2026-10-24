package com.imsvet.ims.purchasing.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A purchase order (FR-PURCH-003/004): numbered at creation (ADR-097), supplier and lines editable only while draft
 * (BR-PURCH-003), totals stored from the lines (ADR-093), status changes through {@link #apply}
 * (BR-PURCH-001), cancel and forced close with a reason (ADR-096, ADR-105).
 */
@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder {

    /** One line as validated by the service. */
    public record LineInput(Long productId, Long unitId, BigDecimal quantity, BigDecimal quantityBase,
            BigDecimal unitPrice, BigDecimal discountPct, BigDecimal vatRate) {
    }

    /** A transition the status table does not allow ({@code INVALID_STATE_TRANSITION}). */
    public static final class IllegalOrderTransitionException extends RuntimeException {

        IllegalOrderTransitionException(OrderStatus from, OrderStatus.Action action) {
            super("Cannot " + action + " an order that is " + from.code());
        }
    }

    /** Null-safe: also applied to an absent query parameter. */
    @Converter
    static class StatusConverter implements AttributeConverter<OrderStatus, String> {

        @Override
        public String convertToDatabaseColumn(OrderStatus status) {
            return status == null ? null : status.code();
        }

        @Override
        public OrderStatus convertToEntityAttribute(String code) {
            return code == null ? null : OrderStatus.fromCode(code).orElseThrow();
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30, updatable = false)
    private String number;

    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    @Convert(converter = StatusConverter.class)
    @Column(nullable = false, length = 30)
    private OrderStatus status;

    @Column(name = "order_date", nullable = false, updatable = false)
    private LocalDate orderDate;

    @Column(name = "expected_date")
    private LocalDate expectedDate;

    @Column(name = "total_ht", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalHt;

    @Column(name = "total_vat", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalVat;

    @Column(name = "total_ttc", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalTtc;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "validated_by")
    private Long validatedBy;

    @Column(name = "validated_at")
    private Instant validatedAt;

    @Column(name = "sent_by")
    private Long sentBy;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "closed_reason", columnDefinition = "TEXT")
    private String closedReason;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<PurchaseOrderLine> lines = new ArrayList<>();

    protected PurchaseOrder() {
    }

    public PurchaseOrder(String number, Long supplierId, LocalDate orderDate, LocalDate expectedDate, Long createdBy,
            Instant now) {
        this.number = number;
        this.supplierId = supplierId;
        this.status = OrderStatus.DRAFT;
        this.orderDate = orderDate;
        this.expectedDate = expectedDate;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
        this.totalHt = BigDecimal.ZERO.setScale(2);
        this.totalVat = BigDecimal.ZERO.setScale(2);
        this.totalTtc = BigDecimal.ZERO.setScale(2);
    }

    /** Draft only: the caller checks the status (BR-PURCH-003). Lines must be flushed away before new ones come. */
    public void clearLines(Long supplier, LocalDate expected, Instant now) {
        this.supplierId = supplier;
        this.expectedDate = expected;
        this.updatedAt = now;
        lines.clear();
    }

    public void addLines(List<LineInput> inputs) {
        for (LineInput in : inputs) {
            lines.add(new PurchaseOrderLine(this, in.productId(), in.unitId(), in.quantity(), in.quantityBase(),
                    in.unitPrice(), in.discountPct(), in.vatRate()));
        }
        this.totalHt = lines.stream().map(PurchaseOrderLine::lineHt).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2);
        this.totalVat = lines.stream().map(PurchaseOrderLine::lineVat).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2);
        this.totalTtc = totalHt.add(totalVat);
    }

    /** {@code reason} is required by cancel and close (checked by the service). */
    public void apply(OrderStatus.Action action, String reason, Long userId, Instant now) {
        OrderStatus next = status.after(action).orElseThrow(() -> new IllegalOrderTransitionException(status, action));
        switch (action) {
            case VALIDATE -> {
                validatedBy = userId;
                validatedAt = now;
            }
            case SEND -> {
                sentBy = userId;
                sentAt = now;
            }
            case CANCEL -> {
                cancelReason = reason;
                cancelledBy = userId;
                cancelledAt = now;
            }
            case CLOSE -> {
                closedReason = reason;
                closedBy = userId;
                closedAt = now;
            }
        }
        this.status = next;
        this.updatedAt = now;
    }

    public Long id() {
        return id;
    }

    public String number() {
        return number;
    }

    public Long supplierId() {
        return supplierId;
    }

    public OrderStatus status() {
        return status;
    }

    public LocalDate orderDate() {
        return orderDate;
    }

    public LocalDate expectedDate() {
        return expectedDate;
    }

    public BigDecimal totalHt() {
        return totalHt;
    }

    public BigDecimal totalVat() {
        return totalVat;
    }

    public BigDecimal totalTtc() {
        return totalTtc;
    }

    public Long createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Long validatedBy() {
        return validatedBy;
    }

    public Instant validatedAt() {
        return validatedAt;
    }

    public Long sentBy() {
        return sentBy;
    }

    public Instant sentAt() {
        return sentAt;
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

    public String closedReason() {
        return closedReason;
    }

    public Long closedBy() {
        return closedBy;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public long version() {
        return version;
    }

    public List<PurchaseOrderLine> lines() {
        return List.copyOf(lines);
    }
}
