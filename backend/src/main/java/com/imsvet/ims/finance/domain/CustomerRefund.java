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

/** Money paid back for a credit note (ADR-128): never changed or removed (BR-PAY-003, SD-102, SD-139). */
@Entity
@Table(name = "customer_refunds")
public class CustomerRefund {

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

    @Column(name = "credit_note_id", nullable = false, updatable = false)
    private Long creditNoteId;

    @Column(name = "customer_id", updatable = false)
    private Long customerId;

    @Convert(converter = MethodConverter.class)
    @Column(nullable = false, updatable = false, length = 30)
    private PaymentMethod method;

    @Column(nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(updatable = false, length = 80)
    private String reference;

    @Column(name = "cash_session_id", updatable = false)
    private Long cashSessionId;

    @Column(name = "refunded_at", nullable = false, updatable = false)
    private Instant refundedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    protected CustomerRefund() {
    }

    public CustomerRefund(Long creditNoteId, Long customerId, PaymentMethod method, BigDecimal amount,
            String reference, Long cashSessionId, Long createdBy, Instant now) {
        this.creditNoteId = creditNoteId;
        this.customerId = customerId;
        this.method = method;
        this.amount = amount;
        this.reference = reference;
        this.cashSessionId = cashSessionId;
        this.createdBy = createdBy;
        this.refundedAt = now;
    }

    public Long id() {
        return id;
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

    public Instant refundedAt() {
        return refundedAt;
    }
}
