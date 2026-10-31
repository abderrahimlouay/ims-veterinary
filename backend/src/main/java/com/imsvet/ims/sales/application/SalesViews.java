package com.imsvet.ims.sales.application;

import com.imsvet.ims.sales.domain.SalesDocument;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** The API shapes of sales documents (api.md §4.10); entities never leave the application layer. */
public final class SalesViews {

    public record CustomerRef(long id, String name) {
    }

    public record Totals(BigDecimal ht, BigDecimal discount, BigDecimal vat, BigDecimal ttc) {
    }

    public record SaleSummary(long id, String docType, String number, String status, CustomerRef customer,
            boolean creditSale, Instant issuedAt, Instant createdAt, BigDecimal totalTtc, long version) {
    }

    public record SaleDetail(long id, String docType, String number, String status, CustomerRef customer, Long parentId,
            boolean creditSale, LocalDate dueDate, Instant issuedAt, Instant createdAt, Instant cancelledAt,
            String notes, Totals totals, List<Object> lines, long version) {
    }

    private SalesViews() {
    }

    static SaleSummary summary(SalesDocument d, CustomerRef customer) {
        return new SaleSummary(d.id(), d.docType().code(), d.number(), d.status().code(), customer, d.creditSale(),
                d.issuedAt(), d.createdAt(), d.totalTtc(), d.version());
    }

    static SaleDetail detail(SalesDocument d, CustomerRef customer) {
        return new SaleDetail(d.id(), d.docType().code(), d.number(), d.status().code(), customer, d.parentId(),
                d.creditSale(), d.dueDate(), d.issuedAt(), d.createdAt(), d.cancelledAt(), d.notes(),
                new Totals(d.totalHt(), d.totalDiscount(), d.totalVat(), d.totalTtc()), List.of(), d.version());
    }
}
