package com.imsvet.ims.reporting.application;

import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.reporting.infrastructure.StockFigures;
import com.imsvet.ims.reporting.infrastructure.StockFigures.ValuationFigure;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The valued stock statement (FR-RPT-002, BR-RPT-002): the stock on hand now, at cost (Σ lot quantity × lot unit cost)
 * or at sale price (Σ quantity × the product's sale price per base unit), by product or category; every lot on hand
 * counts, the unsellable part (blocked, recalled or expired today) shown apart. The cost basis is a cost figure: it
 * needs {@code report.margin} as well (BR-RPT-003).
 */
@Service
public class StockReports {

    private static final int MONEY_SCALE = 2;

    /** {@code key}: the product or category id ({@code null} without a category); quantities only by product. */
    public record ValuationRow(String key, String code, String label, String baseUnit, BigDecimal quantityBase,
            BigDecimal value, BigDecimal unsellableQuantityBase, BigDecimal unsellableValue) {
    }

    public record ValuationReport(LocalDate date, String basis, String groupBy, List<ValuationRow> rows,
            BigDecimal totalValue, BigDecimal totalUnsellableValue) {
    }

    private final StockFigures figures;
    private final BusinessCalendar calendar;

    public StockReports(StockFigures figures, BusinessCalendar calendar) {
        this.figures = figures;
        this.calendar = calendar;
    }

    @PreAuthorize("hasAuthority('report.stock') and (#basis != 'cost' or hasAuthority('report.margin'))")
    @Transactional(readOnly = true)
    public ValuationReport valuation(String basis, String groupBy) {
        boolean atCost = basis(basis);
        boolean product = groupBy == null || groupBy.isBlank() || "product".equals(groupBy);
        if (!product && !"category".equals(groupBy)) {
            throw new ValidationException("Unknown grouping.",
                    List.of(new Violation("groupBy", "UNKNOWN", "category or product.")));
        }
        LocalDate today = calendar.today();
        List<ValuationRow> rows = (product ? figures.byProduct(today) : figures.byCategory(today)).stream()
                .map(f -> row(f, atCost))
                .sorted(Comparator.comparing(ValuationRow::value).reversed()
                        .thenComparing(ValuationRow::label, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        BigDecimal total = rows.stream().map(ValuationRow::value).reduce(zero(), BigDecimal::add);
        BigDecimal unsellable = rows.stream().map(ValuationRow::unsellableValue).reduce(zero(), BigDecimal::add);
        return new ValuationReport(today, atCost ? "cost" : "sale_price", product ? "product" : "category", rows,
                total, unsellable);
    }

    private static ValuationRow row(ValuationFigure f, boolean atCost) {
        return new ValuationRow(f.id() == null ? null : f.id().toString(), f.code(), f.name(), f.baseUnit(),
                f.quantityBase(), money(atCost ? f.valueAtCost() : f.valueAtSalePrice()), f.unsellableQuantityBase(),
                money(atCost ? f.unsellableAtCost() : f.unsellableAtSalePrice()));
    }

    /** {@code sale_price} by default, so that a user without {@code report.margin} reads it. */
    private static boolean basis(String requested) {
        if (requested == null || requested.isBlank() || "sale_price".equals(requested)) {
            return false;
        }
        if ("cost".equals(requested)) {
            return true;
        }
        throw new ValidationException("Unknown basis.", List.of(new Violation("basis", "UNKNOWN", "cost or sale_price.")));
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount == null ? zero() : amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE);
    }
}
