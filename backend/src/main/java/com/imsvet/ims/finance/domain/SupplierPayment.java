package com.imsvet.ims.finance.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** A payment to a supplier or the reversal of one: never changed or removed (BR-PAY-003, ADR-132, ADR-135). */
@Entity
@Table(name = "supplier_payments")
public class SupplierPayment {

    @Converter
    static class MethodConverter implements AttributeConverter<PaymentMethod, String> {
        @Override
        public String convertToDatabaseColumn(PaymentMethod method) {
            return method == null ? null : method.code();
        }

        @Override
        public PaymentMethod convertToEntityAttribute(String code) {
            return code == null ? null : PaymentMethod.fromCode(code).orElseThrow();
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private Long supplierId;

    @Convert(converter = MethodConverter.class)
    @Column(nullable = false, updatable = false, length = 30)
    private PaymentMethod method;

    @Column(nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(updatable = false, length = 80)
    private String reference;

    @Column(name = "cash_session_id", updatable = false)
    private Long cashSessionId;

    @Column(name = "reverses_payment_id", updatable = false)
    private Long reversesPaymentId;

    @Column(name = "reversal_reason", updatable = false, length = 500)
    private String reversalReason;

    @Column(name = "paid_at", nullable = false, updatable = false)
    private Instant paidAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    protected SupplierPayment() {
    }

    public SupplierPayment(Long supplierId, PaymentMethod method, BigDecimal amount, String reference,
            Long cashSessionId, Long createdBy, Instant now) {
        this.supplierId = supplierId;
        this.method = method;
        this.amount = amount;
        this.reference = reference;
        this.cashSessionId = cashSessionId;
        this.createdBy = createdBy;
        this.paidAt = now;
    }

    /** The reversal, its cash (if any) through {@code sessionId}: the reverser's own open session (ADR-135). */
    public SupplierPayment reversal(String reason, Long by, Instant now, Long sessionId) {
        SupplierPayment reversal = new SupplierPayment(supplierId, method, amount, reference, sessionId, by, now);
        reversal.reversesPaymentId = id;
        reversal.reversalReason = reason;
        return reversal;
    }

    public Long id() {
        return id;
    }

    public Long supplierId() {
        return supplierId;
    }

    public PaymentMethod method() {
        return method;
    }

    public BigDecimal amount() {
        return amount;
    }

    public String reference() {
        return reference;
    }

    public Long cashSessionId() {
        return cashSessionId;
    }

    public Long reversesPaymentId() {
        return reversesPaymentId;
    }

    public String reversalReason() {
        return reversalReason;
    }

    public Instant paidAt() {
        return paidAt;
    }
}
