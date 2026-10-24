package com.imsvet.ims.purchasing.domain;

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
import java.time.Instant;

/**
 * A laboratory or wholesaler (FR-PURCH-001, ADR-100): name unique ignoring case and accents, optional tax id,
 * payment terms in days; deactivated, never deleted (BR-PURCH-011).
 */
@Entity
@Table(name = "suppliers")
public class Supplier {

    /** What a supplier is made of, validated by the service. */
    public record Details(String name, SupplierKind kind, String taxId, String phone, String email, String address,
            int paymentTermsDays) {
    }

    @Converter
    static class KindConverter implements AttributeConverter<SupplierKind, String> {

        @Override
        public String convertToDatabaseColumn(SupplierKind kind) {
            return kind == null ? null : kind.code();
        }

        @Override
        public SupplierKind convertToEntityAttribute(String code) {
            return code == null ? null : SupplierKind.fromCode(code).orElseThrow();
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Convert(converter = KindConverter.class)
    @Column(name = "supplier_kind", nullable = false, length = 20)
    private SupplierKind kind;

    @Column(name = "tax_id", length = 40)
    private String taxId;

    @Column(length = 40)
    private String phone;

    @Column(length = 150)
    private String email;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(name = "payment_terms_days", nullable = false)
    private short paymentTermsDays;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Supplier() {
    }

    public Supplier(Details details, Instant now) {
        apply(details);
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(Details details, Instant now) {
        apply(details);
        this.updatedAt = now;
    }

    public void setActive(boolean value, Instant now) {
        if (active != value) {
            this.active = value;
            this.updatedAt = now;
        }
    }

    private void apply(Details d) {
        this.name = d.name();
        this.kind = d.kind();
        this.taxId = d.taxId();
        this.phone = d.phone();
        this.email = d.email();
        this.address = d.address();
        this.paymentTermsDays = (short) d.paymentTermsDays();
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public SupplierKind kind() {
        return kind;
    }

    public String taxId() {
        return taxId;
    }

    public String phone() {
        return phone;
    }

    public String email() {
        return email;
    }

    public String address() {
        return address;
    }

    public int paymentTermsDays() {
        return paymentTermsDays;
    }

    public boolean active() {
        return active;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
