package com.imsvet.ims.sales.application;

import com.imsvet.ims.catalog.application.ProductWithdrawals.Period;
import com.imsvet.ims.inventory.application.SaleLots.LotInfo;
import com.imsvet.ims.sales.domain.SalesDocument;
import com.imsvet.ims.sales.domain.SalesDocumentLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** The API shapes of sales documents (api.md §4.10, §5.5); entities never leave the application layer. */
public final class SalesViews {

    public record CustomerRef(long id, String name) {
    }

    public record Totals(BigDecimal ht, BigDecimal discount, BigDecimal vat, BigDecimal ttc) {
    }

    /** A non-blocking warning; only the fields of its {@code code} are set. */
    public record SaleWarning(String code, long productId, LocalDate expiryDate, String level, Integer speciesId,
            String speciesCode, String speciesName, Short meatDays, Short milkDays, Short eggsDays) {

        static SaleWarning nearExpiry(long productId, LocalDate expiryDate, String level) {
            return new SaleWarning("LOT_NEAR_EXPIRY", productId, expiryDate, level, null, null, null, null, null,
                    null);
        }

        static SaleWarning lowStock(long productId) {
            return new SaleWarning("LOW_STOCK_AFTER_SALE", productId, null, null, null, null, null, null, null, null);
        }

        static SaleWarning withdrawal(long productId, Period p) {
            return new SaleWarning("WITHDRAWAL_PERIOD", productId, null, null, p.speciesId(), p.speciesCode(),
                    p.speciesName(), p.meatDays(), p.milkDays(), p.eggsDays());
        }
    }

    public record SaleLine(long id, long productId, String productName, Long lotId, String lotNumber,
            LocalDate expiryDate, Long unitId, String unitName, Integer targetSpeciesId, BigDecimal quantity,
            BigDecimal quantityBase, BigDecimal unitPrice, BigDecimal discountPct, BigDecimal vatRate,
            BigDecimal lineHt, BigDecimal lineVat, BigDecimal lineTtc, List<SaleWarning> warnings) {
    }

    public record SalePayment(long id, String method, BigDecimal amount, String reference) {
    }

    public record SaleSummary(long id, String docType, String number, String status, CustomerRef customer,
            boolean creditSale, Instant issuedAt, Instant createdAt, BigDecimal totalTtc, long version) {
    }

    public record SaleDetail(long id, String docType, String number, String status, CustomerRef customer, Long parentId,
            boolean creditSale, LocalDate dueDate, Instant issuedAt, Instant createdAt, Instant cancelledAt,
            String notes, Totals totals, List<SaleLine> lines, List<SalePayment> payments, Long cashSessionId,
            long version) {
    }

    private SalesViews() {
    }

    static SaleSummary summary(SalesDocument d, CustomerRef customer) {
        return new SaleSummary(d.id(), d.docType().code(), d.number(), d.status().code(), customer, d.creditSale(),
                d.issuedAt(), d.createdAt(), d.totalTtc(), d.version());
    }

    static SaleDetail detail(SalesDocument d, CustomerRef customer, List<SaleLine> lines,
            List<SalePayment> payments) {
        return new SaleDetail(d.id(), d.docType().code(), d.number(), d.status().code(), customer, d.parentId(),
                d.creditSale(), d.dueDate(), d.issuedAt(), d.createdAt(), d.cancelledAt(), d.notes(),
                new Totals(d.totalHt(), d.totalDiscount(), d.totalVat(), d.totalTtc()), lines, payments, d.cashSessionId(),
                d.version());
    }

    static SaleLine line(SalesDocumentLine l, String productName, String unitName, LotInfo lot,
            List<SaleWarning> warnings) {
        return new SaleLine(l.id(), l.productId(), productName, l.lotId(), lot == null ? null : lot.lotNumber(),
                lot == null ? null : lot.expiryDate(), l.unitId(), unitName, l.targetSpeciesId(), l.quantity(),
                l.quantityBase(), l.unitPrice(), l.discountPct(), l.vatRate(), l.lineHt(), l.lineVat(), l.lineTtc(),
                warnings);
    }
}
