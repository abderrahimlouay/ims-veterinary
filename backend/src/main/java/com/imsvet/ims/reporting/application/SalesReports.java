package com.imsvet.ims.reporting.application;

import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.reporting.infrastructure.SalesFigures;
import com.imsvet.ims.reporting.infrastructure.SalesFigures.DocumentFigure;
import com.imsvet.ims.reporting.infrastructure.SalesFigures.GroupFigure;
import com.imsvet.ims.reporting.infrastructure.SalesFigures.StatisticFigure;
import com.imsvet.ims.reporting.infrastructure.SalesFigures.Uninvoiced;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revenue, gross margin and sales statistics (FR-RPT-001, FR-RPT-003, BR-RPT-001, ADR-147): confirmed invoices and receipts at their issue date,
 * net of credit notes at theirs; a delivery note through its invoice only, the uninvoiced ones given apart. Periods are
 * business dates, both inclusive (business time zone); without {@code from} the month of {@code to}, without {@code to}
 * today. Margin and cost need {@code report.margin} (BR-RPT-003).
 */
@Service
public class SalesReports {

    private static final int MONEY_SCALE = 2;
    private static final Set<String> REVENUE_GROUPS = Set.of("day", "month", "category", "product");
    private static final Set<String> MARGIN_GROUPS = Set.of("category", "product");
    private static final Set<String> STATISTIC_GROUPS = Set.of("day", "month", "customer", "wilaya", "commune");
    private static final Set<String> CUSTOMER_TYPES = Set.of("breeder", "veterinarian", "other");

    /**
     * {@code key}: the business date ({@code 2026-11-04}), the month ({@code 2026-11}), or the product or category id
     * ({@code null} for products without a category); {@code code} is the product's SKU.
     */
    public record RevenueRow(String key, String code, String label, BigDecimal revenueHt) {
    }

    public record UninvoicedDeliveryNotes(long count, BigDecimal totalHt) {
    }

    public record RevenueReport(LocalDate from, LocalDate to, String groupBy, List<RevenueRow> rows,
            BigDecimal totalHt, UninvoicedDeliveryNotes uninvoicedDeliveryNotes) {
    }

    /** {@code marginRate}: margin over revenue in percent, {@code null} when the revenue is zero. */
    public record MarginRow(String key, String code, String label, BigDecimal quantityBase, BigDecimal revenueHt,
            BigDecimal cost, BigDecimal margin, BigDecimal marginRate) {
    }

    public record MarginReport(LocalDate from, LocalDate to, String groupBy, List<MarginRow> rows,
            BigDecimal totalRevenueHt, BigDecimal totalCost, BigDecimal totalMargin, BigDecimal marginRate) {
    }

    /**
     * {@code key}: the date or month, or the customer id; {@code null} for counter sales without a customer or a
     * customer without the zone. {@code code}: the wilaya of a commune. {@code documents}: invoices and receipts.
     */
    public record StatisticRow(String key, String code, String label, long documents, BigDecimal revenueHt) {
    }

    public record StatisticsReport(LocalDate from, LocalDate to, String groupBy, String customerType,
            List<StatisticRow> rows, long totalDocuments, BigDecimal totalHt) {
    }

    private record Period(LocalDate from, LocalDate to, Instant start, Instant end) {
    }

    private final SalesFigures figures;
    private final BusinessCalendar calendar;

    public SalesReports(SalesFigures figures, BusinessCalendar calendar) {
        this.figures = figures;
        this.calendar = calendar;
    }

    @PreAuthorize("hasAuthority('report.sales')")
    @Transactional(readOnly = true)
    public RevenueReport revenue(LocalDate from, LocalDate to, String groupBy) {
        String group = groupBy(groupBy, "month", REVENUE_GROUPS);
        Period p = period(from, to);
        List<RevenueRow> rows = switch (group) {
            case "day", "month" -> byDate(p, "day".equals(group));
            case "product" -> sorted(figures.byProduct(p.start(), p.end())).stream()
                    .map(f -> new RevenueRow(key(f), f.code(), f.name(), money(f.revenueHt()))).toList();
            default -> sorted(figures.byCategory(p.start(), p.end())).stream()
                    .map(f -> new RevenueRow(key(f), null, f.name(), money(f.revenueHt()))).toList();
        };
        BigDecimal total = rows.stream().map(RevenueRow::revenueHt).reduce(zero(), BigDecimal::add);
        Uninvoiced u = figures.uninvoiced(p.start(), p.end());
        return new RevenueReport(p.from(), p.to(), group, rows, total,
                new UninvoicedDeliveryNotes(u.count(), money(u.totalHt())));
    }

    @PreAuthorize("hasAuthority('report.margin')")
    @Transactional(readOnly = true)
    public MarginReport margin(LocalDate from, LocalDate to, String groupBy) {
        String group = groupBy(groupBy, "category", MARGIN_GROUPS);
        Period p = period(from, to);
        boolean product = "product".equals(group);
        List<GroupFigure> found = product ? figures.byProduct(p.start(), p.end()) : figures.byCategory(p.start(), p.end());
        List<MarginRow> rows = new ArrayList<>();
        BigDecimal revenue = zero();
        BigDecimal cost = zero();
        for (GroupFigure f : sorted(found)) {
            BigDecimal r = money(f.revenueHt());
            BigDecimal c = money(f.cost());
            revenue = revenue.add(r);
            cost = cost.add(c);
            rows.add(new MarginRow(key(f), f.code(), f.name(), f.quantityBase(), r, c, r.subtract(c),
                    rate(r.subtract(c), r)));
        }
        return new MarginReport(p.from(), p.to(), group, rows, revenue, cost, revenue.subtract(cost),
                rate(revenue.subtract(cost), revenue));
    }

    /**
     * Sales statistics (FR-RPT-003) by period, customer or zone, for every customer or one type ({@code breeder} for
     * the breeders); the revenue as in {@link #revenue}.
     */
    @PreAuthorize("hasAuthority('report.sales')")
    @Transactional(readOnly = true)
    public StatisticsReport statistics(LocalDate from, LocalDate to, String groupBy, String customerType) {
        String group = groupBy(groupBy, "month", STATISTIC_GROUPS);
        String type = customerType(customerType);
        Period p = period(from, to);
        List<StatisticRow> rows = switch (group) {
            case "day", "month" -> statisticsByDate(p, "day".equals(group), type);
            case "customer" -> statistics(figures.byCustomer(p.start(), p.end(), type));
            case "wilaya" -> statistics(figures.byWilaya(p.start(), p.end(), type));
            default -> statistics(figures.byCommune(p.start(), p.end(), type));
        };
        long documents = rows.stream().mapToLong(StatisticRow::documents).sum();
        BigDecimal total = rows.stream().map(StatisticRow::revenueHt).reduce(zero(), BigDecimal::add);
        return new StatisticsReport(p.from(), p.to(), group, type, rows, documents, total);
    }

    private List<StatisticRow> statisticsByDate(Period p, boolean daily, String type) {
        Map<String, BigDecimal> totals = new TreeMap<>();
        Map<String, Long> counts = new TreeMap<>();
        for (DocumentFigure f : figures.byDocument(p.start(), p.end(), type)) {
            LocalDate date = calendar.dateOf(f.issuedAt());
            String key = daily ? date.toString() : YearMonth.from(date).toString();
            totals.merge(key, money(f.revenueHt()), BigDecimal::add);
            counts.merge(key, f.creditNote() ? 0L : 1L, Long::sum);
        }
        return totals.entrySet().stream()
                .map(e -> new StatisticRow(e.getKey(), null, null, counts.get(e.getKey()), e.getValue())).toList();
    }

    private static List<StatisticRow> statistics(List<StatisticFigure> found) {
        return found.stream()
                .map(f -> new StatisticRow(f.id() == null ? null : f.id().toString(), f.code(), f.name(),
                        f.documents(), money(f.revenueHt())))
                .sorted(Comparator.comparing(StatisticRow::revenueHt).reversed()
                        .thenComparing(StatisticRow::label, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private static String customerType(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        if (!CUSTOMER_TYPES.contains(requested)) {
            throw new ValidationException("Unknown customer type.", List.of(new Violation("customerType", "UNKNOWN",
                    "breeder, veterinarian or other.")));
        }
        return requested;
    }

    private List<RevenueRow> byDate(Period p, boolean daily) {
        Map<String, BigDecimal> totals = new TreeMap<>();
        for (DocumentFigure f : figures.byDocument(p.start(), p.end(), null)) {
            LocalDate date = calendar.dateOf(f.issuedAt());
            String key = daily ? date.toString() : YearMonth.from(date).toString();
            totals.merge(key, money(f.revenueHt()), BigDecimal::add);
        }
        return totals.entrySet().stream().map(e -> new RevenueRow(e.getKey(), null, null, e.getValue())).toList();
    }

    private Period period(LocalDate from, LocalDate to) {
        LocalDate last = to == null ? calendar.today() : to;
        LocalDate first = from == null ? last.withDayOfMonth(1) : from;
        if (first.isAfter(last)) {
            throw new ValidationException("The period is invalid.",
                    List.of(new Violation("from", "RANGE", "The start date must not be after the end date.")));
        }
        return new Period(first, last, calendar.startOf(first), calendar.startOf(last.plusDays(1)));
    }

    private static String groupBy(String requested, String fallback, Set<String> allowed) {
        if (requested == null || requested.isBlank()) {
            return fallback;
        }
        if (!allowed.contains(requested)) {
            throw new ValidationException("Unknown grouping.", List.of(new Violation("groupBy", "UNKNOWN",
                    "One of " + String.join(", ", allowed.stream().sorted().toList()) + ".")));
        }
        return requested;
    }

    /** Highest revenue first, then by name. */
    private static List<GroupFigure> sorted(List<GroupFigure> found) {
        return found.stream().sorted(Comparator.comparing(GroupFigure::revenueHt).reversed()
                .thenComparing(GroupFigure::name, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }

    private static String key(GroupFigure f) {
        return f.id() == null ? null : f.id().toString();
    }

    private static BigDecimal rate(BigDecimal margin, BigDecimal revenue) {
        return revenue.signum() == 0 ? null
                : margin.multiply(BigDecimal.valueOf(100)).divide(revenue, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount == null ? zero() : amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE);
    }
}
