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

/** An entry of a session's ledger: never changed or removed (BR-PAY-003, SD-102); errors are reversed. */
@Entity
@Table(name = "cash_movements")
public class CashMovement {

    @Converter
    static class DirectionConverter implements AttributeConverter<CashDirection, String> {
        @Override
        public String convertToDatabaseColumn(CashDirection direction) {
            return direction == null ? null : direction.code();
        }

        @Override
        public CashDirection convertToEntityAttribute(String code) {
            return code == null ? null : CashDirection.fromCode(code);
        }
    }

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

    @Column(name = "cash_session_id", nullable = false, updatable = false)
    private Long cashSessionId;

    @Convert(converter = DirectionConverter.class)
    @Column(nullable = false, updatable = false, length = 30)
    private CashDirection direction;

    @Convert(converter = MethodConverter.class)
    @Column(nullable = false, updatable = false, length = 30)
    private PaymentMethod method;

    @Column(nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(updatable = false, length = 200)
    private String label;

    @Column(name = "customer_payment_id", updatable = false)
    private Long customerPaymentId;

    @Column(name = "customer_refund_id", updatable = false)
    private Long customerRefundId;

    @Column(name = "supplier_payment_id", updatable = false)
    private Long supplierPaymentId;

    @Column(nullable = false, updatable = false)
    private boolean manual;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CashMovement() {
    }

    public CashMovement(Long cashSessionId, CashDirection direction, PaymentMethod method, BigDecimal amount,
            String label, Long customerPaymentId, Long createdBy, Instant now) {
        this.cashSessionId = cashSessionId;
        this.direction = direction;
        this.method = method;
        this.amount = amount;
        this.label = label;
        this.customerPaymentId = customerPaymentId;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    /** The cash paid out for a customer refund (ADR-128). */
    public static CashMovement refund(Long cashSessionId, BigDecimal amount, String label, Long customerRefundId,
            Long createdBy, Instant now) {
        CashMovement movement = new CashMovement(cashSessionId, CashDirection.OUT, PaymentMethod.CASH, amount, label,
                null, createdBy, now);
        movement.customerRefundId = customerRefundId;
        return movement;
    }

    /** The cash of a supplier payment (OUT) or of its reversal (IN) (ADR-132, ADR-135). */
    public static CashMovement supplierPayment(Long cashSessionId, CashDirection direction, BigDecimal amount,
            String label, Long supplierPaymentId, Long createdBy, Instant now) {
        CashMovement movement = new CashMovement(cashSessionId, direction, PaymentMethod.CASH, amount, label, null,
                createdBy, now);
        movement.supplierPaymentId = supplierPaymentId;
        return movement;
    }

    /** A manual movement (ADR-136): cash only, its label mandatory, no other source. */
    public static CashMovement manual(Long cashSessionId, CashDirection direction, BigDecimal amount, String label,
            Long createdBy, Instant now) {
        CashMovement movement = new CashMovement(cashSessionId, direction, PaymentMethod.CASH, amount, label, null,
                createdBy, now);
        movement.manual = true;
        return movement;
    }

    public Long id() {
        return id;
    }

    public CashDirection direction() {
        return direction;
    }

    public PaymentMethod method() {
        return method;
    }

    public String label() {
        return label;
    }

    public Long customerPaymentId() {
        return customerPaymentId;
    }

    public Long customerRefundId() {
        return customerRefundId;
    }

    public Long supplierPaymentId() {
        return supplierPaymentId;
    }

    public boolean manual() {
        return manual;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public BigDecimal amount() {
        return amount;
    }
}
