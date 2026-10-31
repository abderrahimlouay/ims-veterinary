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
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

/** A cashier's session (FR-FIN-007): opened with a declared amount, closed once with the counted amount. */
@Entity
@Table(name = "cash_sessions")
public class CashSession {

    @Converter
    static class StatusConverter implements AttributeConverter<CashSessionStatus, String> {
        @Override
        public String convertToDatabaseColumn(CashSessionStatus status) {
            return status == null ? null : status.code();
        }

        @Override
        public CashSessionStatus convertToEntityAttribute(String code) {
            return code == null ? null : CashSessionStatus.fromCode(code);
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cashier_id", nullable = false, updatable = false)
    private Long cashierId;

    @Convert(converter = StatusConverter.class)
    @Column(nullable = false, length = 30)
    private CashSessionStatus status = CashSessionStatus.OPEN;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(name = "opening_amount", nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal openingAmount;

    @Column(name = "expected_amount", precision = 14, scale = 2)
    private BigDecimal expectedAmount;

    @Column(name = "counted_amount", precision = 14, scale = 2)
    private BigDecimal countedAmount;

    @Version
    private long version;

    protected CashSession() {
    }

    public CashSession(Long cashierId, BigDecimal openingAmount, Instant now) {
        this.cashierId = cashierId;
        this.openingAmount = openingAmount;
        this.openedAt = now;
    }

    public void close(BigDecimal expected, BigDecimal counted, Long by, Instant now) {
        this.status = CashSessionStatus.CLOSED;
        this.expectedAmount = expected;
        this.countedAmount = counted;
        this.closedBy = by;
        this.closedAt = now;
    }

    public boolean open() {
        return status == CashSessionStatus.OPEN;
    }

    /** {@code null} while open. */
    public BigDecimal varianceAmount() {
        return countedAmount == null ? null : CashAmounts.variance(countedAmount, expectedAmount);
    }

    public Long id() {
        return id;
    }

    public Long cashierId() {
        return cashierId;
    }

    public CashSessionStatus status() {
        return status;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public Long closedBy() {
        return closedBy;
    }

    public BigDecimal openingAmount() {
        return openingAmount;
    }

    public BigDecimal expectedAmount() {
        return expectedAmount;
    }

    public BigDecimal countedAmount() {
        return countedAmount;
    }

    public long version() {
        return version;
    }
}
