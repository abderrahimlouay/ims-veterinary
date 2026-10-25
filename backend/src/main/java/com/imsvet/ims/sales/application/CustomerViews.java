package com.imsvet.ims.sales.application;

import com.imsvet.ims.sales.domain.CustomerCategory;
import java.math.BigDecimal;
import java.time.Instant;

/** api.md §4.9 customer DTOs. */
public final class CustomerViews {

    public record CategoryView(int id, String name, BigDecimal discountPct, Instant updatedAt, long version) {
    }

    public record CategoryRef(int id, String name, BigDecimal discountPct) {
    }

    public record CustomerRef(long id, String name) {
    }

    public record CustomerSummary(long id, String name, String customerType, CategoryRef category, String wilaya,
            String commune, String phone, boolean active) {
    }

    public record CustomerDetail(long id, String name, String customerType, CategoryRef category, String farmingType,
            String wilaya, String commune, String address, String phone, String email, String taxId,
            int paymentTermsDays, CustomerRef veterinarian, BigDecimal creditLimit, boolean active, Instant createdAt,
            Instant updatedAt, long version) {
    }

    /** A possible duplicate (ADR-110): the other customer's id and the matching field only. */
    public record SimilarCustomer(long customerId, String field) {
    }

    static CategoryView category(CustomerCategory c) {
        return new CategoryView(c.id(), c.name(), c.discountPct(), c.updatedAt(), c.version());
    }

    private CustomerViews() {
    }
}
