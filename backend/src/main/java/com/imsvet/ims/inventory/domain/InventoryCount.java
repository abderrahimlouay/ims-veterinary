package com.imsvet.ims.inventory.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;

/**
 * An inventory count session (W5, FR-STOCK-006, database.md §3.4): a full count, or a cycle count scoped by one
 * category or a product list (ADR-073). Only an open count changes (BR-INV-004); at most one full count is open
 * (BR-INV-007, also a unique generated column).
 */
@Entity
@Table(name = "inventories")
public class InventoryCount {

    @Converter
    static class TypeConverter implements AttributeConverter<CountType, String> {

        @Override
        public String convertToDatabaseColumn(CountType type) {
            return type == null ? null : type.code();
        }

        @Override
        public CountType convertToEntityAttribute(String code) {
            return code == null ? null : CountType.fromCode(code).orElseThrow();
        }
    }

    /** Null-safe: also applied to an absent query parameter. */
    @Converter
    static class StatusConverter implements AttributeConverter<CountStatus, String> {

        @Override
        public String convertToDatabaseColumn(CountStatus status) {
            return status == null ? null : status.code();
        }

        @Override
        public CountStatus convertToEntityAttribute(String code) {
            return code == null ? null : CountStatus.fromCode(code).orElseThrow();
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Convert(converter = TypeConverter.class)
    @Column(name = "inv_type", nullable = false, length = 30, updatable = false)
    private CountType type;

    @Convert(converter = StatusConverter.class)
    @Column(nullable = false, length = 30)
    private CountStatus status;

    @Column(name = "scope_category_id", updatable = false)
    private Integer scopeCategoryId;

    @ElementCollection
    @CollectionTable(name = "inventory_scope_products", joinColumns = @JoinColumn(name = "inventory_id"))
    @Column(name = "product_id")
    private Set<Long> scopeProductIds = new TreeSet<>();

    @Column(name = "started_by", nullable = false, updatable = false)
    private Long startedBy;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Version
    private long version;

    protected InventoryCount() {
    }

    private InventoryCount(CountType type, Integer scopeCategoryId, Set<Long> scopeProductIds, String notes,
            Long startedBy, Instant startedAt) {
        this.type = type;
        this.status = CountStatus.OPEN;
        this.scopeCategoryId = scopeCategoryId;
        this.scopeProductIds.addAll(scopeProductIds);
        this.notes = notes;
        this.startedBy = startedBy;
        this.startedAt = startedAt;
    }

    public static InventoryCount full(String notes, Long startedBy, Instant startedAt) {
        return new InventoryCount(CountType.FULL, null, Set.of(), notes, startedBy, startedAt);
    }

    public static InventoryCount cycleOfCategory(int categoryId, String notes, Long startedBy, Instant startedAt) {
        return new InventoryCount(CountType.CYCLE, categoryId, Set.of(), notes, startedBy, startedAt);
    }

    public static InventoryCount cycleOfProducts(Set<Long> productIds, String notes, Long startedBy,
            Instant startedAt) {
        return new InventoryCount(CountType.CYCLE, null, productIds, notes, startedBy, startedAt);
    }

    public boolean open() {
        return status == CountStatus.OPEN;
    }

    /** Only an open count is cancelled; the caller checks {@link #open()} first. */
    public void cancel(Long userId, Instant now) {
        if (!open()) {
            throw new IllegalStateException("Count " + id + " is " + status.code());
        }
        this.status = CountStatus.CANCELLED;
        this.closedBy = userId;
        this.closedAt = now;
    }

    public Long id() {
        return id;
    }

    public CountType type() {
        return type;
    }

    public CountStatus status() {
        return status;
    }

    public Integer scopeCategoryId() {
        return scopeCategoryId;
    }

    public Set<Long> scopeProductIds() {
        return Set.copyOf(scopeProductIds);
    }

    public Long startedBy() {
        return startedBy;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public Long closedBy() {
        return closedBy;
    }

    public String notes() {
        return notes;
    }

    public long version() {
        return version;
    }
}
