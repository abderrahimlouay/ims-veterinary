package com.imsvet.ims.inventory.domain;

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
import org.hibernate.annotations.Immutable;

/**
 * One line of the stock ledger (ADR-028, BR-STOCK-002/004): signed base-unit quantity on one lot, by an operator, at
 * a time, possibly for a document. Written once by {@code StockService}, never changed or removed (BR-STOCK-003).
 */
@Entity
@Immutable
@Table(name = "stock_movements")
public class StockMovement {

    /** Null-safe: also applied to an absent query parameter. */
    @Converter
    static class TypeConverter implements AttributeConverter<MovementType, String> {

        @Override
        public String convertToDatabaseColumn(MovementType type) {
            return type == null ? null : type.code();
        }

        @Override
        public MovementType convertToEntityAttribute(String code) {
            return code == null ? null : MovementType.fromCode(code);
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lot_id", nullable = false)
    private Long lotId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Convert(converter = TypeConverter.class)
    @Column(name = "movement_type", nullable = false, length = 30)
    private MovementType type;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    @Column(name = "reference_type", length = 40)
    private String referenceType;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StockMovement() {
    }

    public StockMovement(long lotId, long productId, MovementType type, BigDecimal quantity, String referenceType,
            Long referenceId, String reason, long userId, Instant createdAt) {
        this.lotId = lotId;
        this.productId = productId;
        this.type = type;
        this.quantity = quantity;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.reason = reason;
        this.userId = userId;
        this.createdAt = createdAt;
    }

    public Long id() {
        return id;
    }

    public Long lotId() {
        return lotId;
    }

    public Long productId() {
        return productId;
    }

    public MovementType type() {
        return type;
    }

    public BigDecimal quantity() {
        return quantity;
    }

    public String referenceType() {
        return referenceType;
    }

    public Long referenceId() {
        return referenceId;
    }

    public String reason() {
        return reason;
    }

    public Long userId() {
        return userId;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
