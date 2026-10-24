package com.imsvet.ims.purchasing.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** api.md §4.7 purchase-order DTOs. */
public final class OrderViews {

    public record SupplierRef(long id, String name, boolean active) {
    }

    public record ProductRef(long id, String sku, String commercialName, String baseUnit) {
    }

    public record UnitRef(long id, String name, BigDecimal factorToBase) {
    }

    public record UserRef(long id, String fullName) {
    }

    public record OrderLine(long id, ProductRef product, UnitRef unit, BigDecimal quantity, BigDecimal quantityBase,
            BigDecimal receivedBase, BigDecimal outstandingBase, BigDecimal unitPrice, BigDecimal discountPct,
            BigDecimal vatRate, BigDecimal lineHt, BigDecimal lineVat) {
    }

    /** Receipts of the order, oldest first. */
    public record OrderReceipt(long id, String number, Instant receivedAt, String status) {
    }

    public record PurchaseOrderSummary(long id, String number, SupplierRef supplier, String status,
            LocalDate orderDate, LocalDate expectedDate, BigDecimal totalTtc) {
    }

    public record PurchaseOrderDetail(long id, String number, SupplierRef supplier, String status,
            LocalDate orderDate, LocalDate expectedDate, List<OrderLine> lines, BigDecimal totalHt,
            BigDecimal totalVat, BigDecimal totalTtc, UserRef createdBy, Instant createdAt, Instant validatedAt,
            Instant sentAt, String cancelReason, UserRef cancelledBy, Instant cancelledAt, String closedReason,
            UserRef closedBy, Instant closedAt, List<OrderReceipt> receipts, long version) {
    }

    /** A price suggestion for an order line (ADR-099): {@code source} {@code history} or {@code product}. */
    public record PricePrefill(BigDecimal unitPrice, BigDecimal discountPct, BigDecimal vatRate, String source) {
    }

    private OrderViews() {
    }
}
