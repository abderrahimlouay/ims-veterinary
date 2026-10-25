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

/**
 * A customer (FR-SALE-004, ADR-109): identity, contact, location, category, follow-up veterinarian, payment terms
 * and credit limit (ADR-108; a new customer starts at 0, cash only). The keys of {@link CustomerKeys} are kept
 * with the values they normalise. Deactivated, never deleted. Personal data: never logged nor audited (ADR-114).
 */
@Entity
@Table(name = "customers")
public class Customer {

    /** The editable fields, already validated. */
    public record Details(String name, CustomerType type, Integer categoryId, String farmingType, String wilaya,
            String commune, String address, String phone, String email, String taxId, int paymentTermsDays,
            Long veterinarianId) {
    }

    @Converter
    static class TypeConverter implements AttributeConverter<CustomerType, String> {

        @Override
        public String convertToDatabaseColumn(CustomerType type) {
            return type == null ? null : type.code();
        }

        @Override
        public CustomerType convertToEntityAttribute(String code) {
            return code == null ? null : CustomerType.fromCode(code).orElseThrow();
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Convert(converter = TypeConverter.class)
    @Column(name = "customer_type", nullable = false, length = 30)
    private CustomerType type;

    @Column(name = "category_id")
    private Integer categoryId;

    @Column(name = "farming_type", length = 100)
    private String farmingType;

    @Column(length = 60)
    private String wilaya;

    @Column(length = 80)
    private String commune;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(length = 40)
    private String phone;

    @Column(name = "phone_key", length = 20)
    private String phoneKey;

    @Column(length = 150)
    private String email;

    @Column(name = "tax_id", length = 40)
    private String taxId;

    @Column(name = "payment_terms_days", nullable = false)
    private int paymentTermsDays;

    @Column(name = "veterinarian_id")
    private Long veterinarianId;

    @Column(name = "credit_limit", precision = 14, scale = 2)
    private BigDecimal creditLimit;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Customer() {
    }

    public Customer(Details details, Instant now) {
        apply(details);
        this.creditLimit = BigDecimal.ZERO.setScale(2);
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(Details details, Instant now) {
        apply(details);
        this.updatedAt = now;
    }

    /** Only through the credit-limit use case (ADR-108); {@code null} = no limit, 0 = cash only. */
    public void changeCreditLimit(BigDecimal limit, Instant now) {
        this.creditLimit = limit;
        this.updatedAt = now;
    }

    public void setActive(boolean active, Instant now) {
        this.active = active;
        this.updatedAt = now;
    }

    private void apply(Details d) {
        this.name = d.name();
        this.type = d.type();
        this.categoryId = d.categoryId();
        this.farmingType = d.farmingType();
        this.wilaya = d.wilaya();
        this.commune = d.commune();
        this.address = d.address();
        this.phone = d.phone();
        this.phoneKey = CustomerKeys.phoneKey(d.phone());
        this.email = d.email();
        this.taxId = CustomerKeys.taxKey(d.taxId());
        this.paymentTermsDays = d.paymentTermsDays();
        this.veterinarianId = d.veterinarianId();
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public CustomerType type() {
        return type;
    }

    public Integer categoryId() {
        return categoryId;
    }

    public String farmingType() {
        return farmingType;
    }

    public String wilaya() {
        return wilaya;
    }

    public String commune() {
        return commune;
    }

    public String address() {
        return address;
    }

    public String phone() {
        return phone;
    }

    public String phoneKey() {
        return phoneKey;
    }

    public String email() {
        return email;
    }

    public String taxId() {
        return taxId;
    }

    public int paymentTermsDays() {
        return paymentTermsDays;
    }

    public Long veterinarianId() {
        return veterinarianId;
    }

    public BigDecimal creditLimit() {
        return creditLimit;
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
