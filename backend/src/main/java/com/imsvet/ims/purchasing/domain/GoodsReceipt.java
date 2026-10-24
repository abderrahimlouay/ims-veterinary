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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A goods receipt (FR-PURCH-005, W1b): created confirmed with its GR number (ADR-097), for an order or without
 * (ADR-096); its lines name their lot, base quantity, cost per base unit, conformity, movement and what they
 * changed on the lot (ADR-106). Never edited; cancelled as a whole (ADR-103).
 */
@Entity
@Table(name = "goods_receipts")
public class GoodsReceipt {

    /** One received line, after the lot entry. */
    public record LineInput(Long poLineId, long productId, long lotId, BigDecimal quantityBase, BigDecimal unitCost,
            boolean conforming, long movementId, boolean lotCreated, BigDecimal lotCostBefore,
            String lotStatusBefore, boolean lotBlocked) {
    }

    @Converter
    static class StatusConverter implements AttributeConverter<ReceiptStatus, String> {

        @Override
        public String convertToDatabaseColumn(ReceiptStatus status) {
            return status == null ? null : status.code();
        }

        @Override
        public ReceiptStatus convertToEntityAttribute(String code) {
            return code == null ? null : ReceiptStatus.fromCode(code).orElseThrow();
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30, updatable = false)
    private String number;

    @Column(name = "purchase_order_id", updatable = false)
    private Long purchaseOrderId;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private Long supplierId;

    @Column(name = "supplier_doc_ref", length = 60, updatable = false)
    private String supplierDocRef;

    @Column(columnDefinition = "TEXT", updatable = false)
    private String notes;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "received_by", nullable = false, updatable = false)
    private Long receivedBy;

    @Convert(converter = StatusConverter.class)
    @Column(nullable = false, length = 15)
    private ReceiptStatus status;

    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL)
    @OrderBy("id")
    private List<GoodsReceiptLine> lines = new ArrayList<>();

    protected GoodsReceipt() {
    }

    public GoodsReceipt(String number, Long purchaseOrderId, Long supplierId, String supplierDocRef, String notes,
            Long receivedBy, Instant receivedAt) {
        this.number = number;
        this.purchaseOrderId = purchaseOrderId;
        this.supplierId = supplierId;
        this.supplierDocRef = supplierDocRef;
        this.notes = notes;
        this.receivedBy = receivedBy;
        this.receivedAt = receivedAt;
        this.status = ReceiptStatus.CONFIRMED;
    }

    public void addLines(List<LineInput> inputs) {
        for (LineInput in : inputs) {
            lines.add(new GoodsReceiptLine(this, in));
        }
    }

    public Long id() {
        return id;
    }

    public String number() {
        return number;
    }

    public Long purchaseOrderId() {
        return purchaseOrderId;
    }

    public Long supplierId() {
        return supplierId;
    }

    public String supplierDocRef() {
        return supplierDocRef;
    }

    public String notes() {
        return notes;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public Long receivedBy() {
        return receivedBy;
    }

    public ReceiptStatus status() {
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

    public List<GoodsReceiptLine> lines() {
        return List.copyOf(lines);
    }
}
