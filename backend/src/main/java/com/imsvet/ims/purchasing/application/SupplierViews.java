package com.imsvet.ims.purchasing.application;

import com.imsvet.ims.purchasing.domain.Supplier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** api.md §4.6 supplier DTOs. */
public final class SupplierViews {

    public record SupplierDetail(long id, String name, String kind, String taxId, String phone, String email,
            String address, int paymentTermsDays, boolean active, Instant createdAt, Instant updatedAt,
            long version) {
    }

    public record PriceProduct(long id, String sku, String commercialName, String baseUnit) {
    }

    public record UserRef(long id, String fullName) {
    }

    /**
     * A price-history entry: price per base unit (ADR-094); {@code receiptId} when a receipt appended it,
     * {@code cancelled} once that receipt is cancelled (shown struck through, ADR-106).
     */
    public record PriceEntry(long id, PriceProduct product, BigDecimal unitPrice, BigDecimal discountPct,
            LocalDate validFrom, Instant createdAt, UserRef createdBy, Long receiptId, boolean cancelled) {
    }

    private SupplierViews() {
    }

    static SupplierDetail detail(Supplier s) {
        return new SupplierDetail(s.id(), s.name(), s.kind().code(), s.taxId(), s.phone(), s.email(), s.address(),
                s.paymentTermsDays(), s.active(), s.createdAt(), s.updatedAt(), s.version());
    }
}
