package com.imsvet.ims.inventory.application;

import com.imsvet.ims.catalog.application.ProductStock;
import com.imsvet.ims.inventory.domain.ExpiryPolicy;
import com.imsvet.ims.inventory.domain.LotStatus;
import com.imsvet.ims.inventory.domain.StockStatus;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The catalog's {@link ProductStock} port, implemented by the module that owns stock (ADR-090). The named exception
 * of architecture.md §3 rule 2: its constant JPQL conditions read {@code Lot} and the product alias {@code p}
 * ({@code p.minStock}, {@code p.reorderPoint}); every value — the business date from the injected clock, the active
 * status — is a bind parameter, so nothing outside can change the text ({@code ModuleSqlBoundaryTest}).
 */
@Component
public class ProductStockConditions implements ProductStock {

    /** Sellable stock of {@code p} (BR-STOCK-011): stored active and not expired at the business date. */
    private static final String SELLABLE = "(SELECT COALESCE(SUM(sl.quantityOnHand), 0) FROM Lot sl"
            + " WHERE sl.productId = p.id AND sl.status = :stockActiveStatus AND sl.expiryDate >= :stockBusinessDate)";

    /**
     * One sellable subquery per condition (measured: two made the low-stock list ~0.55 s on 50,000 products).
     * In stock: {@code min_stock ≥ 0} (CHECK), so above the minimum is above zero. Low: quantities have 3 decimals,
     * so {@code > 0} is {@code ≥ 0.001}.
     */
    private static final Map<StockFilter, String> CONDITIONS = Map.of(
            StockFilter.HAS_SELLABLE, "EXISTS (SELECT 1 FROM Lot sl WHERE sl.productId = p.id"
                    + " AND sl.status = :stockActiveStatus AND sl.quantityOnHand > 0"
                    + " AND sl.expiryDate >= :stockBusinessDate)",
            StockFilter.IN_STOCK, SELLABLE + " > p.minStock",
            StockFilter.LOW, SELLABLE + " BETWEEN :stockSmallestQuantity AND p.minStock",
            StockFilter.OUT, SELLABLE + " = 0",
            StockFilter.BELOW_REORDER, "p.reorderPoint > 0 AND " + SELLABLE + " <= p.reorderPoint");

    private static final int QUANTITY_SCALE = 3;

    private static final BigDecimal NONE = BigDecimal.ZERO.setScale(QUANTITY_SCALE);

    /** The smallest positive base quantity (3 decimals). */
    private static final BigDecimal SMALLEST = new BigDecimal("0.001");

    private final ExpiryPolicies policies;
    private final LotRepository lots;

    public ProductStockConditions(ExpiryPolicies policies, LotRepository lots) {
        this.policies = policies;
        this.lots = lots;
    }

    @Override
    public Condition condition(StockFilter filter) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("stockActiveStatus", LotStatus.ACTIVE);
        parameters.put("stockBusinessDate", policies.current().businessDate());
        if (filter == StockFilter.LOW) {
            parameters.put("stockSmallestQuantity", SMALLEST);
        }
        return new Condition(CONDITIONS.get(filter), parameters);
    }

    @Override
    public Map<Long, Figures> figures(Collection<ProductThresholds> products) {
        if (products.isEmpty()) {
            return Map.of();
        }
        ExpiryPolicy policy = policies.current();
        List<Long> ids = products.stream().map(ProductThresholds::productId).distinct().toList();
        Map<Long, Object[]> rows = new HashMap<>();
        for (Object[] row : lots.stockFigures(ids, LotStatus.ACTIVE, policy.businessDate())) {
            rows.put((Long) row[0], row);
        }
        Map<Long, Figures> figures = new HashMap<>();
        for (ProductThresholds p : products) {
            Object[] row = rows.get(p.productId());
            BigDecimal total = row == null ? NONE : quantity(row[1]);
            BigDecimal sellable = row == null ? NONE : quantity(row[2]);
            LocalDate nearest = row == null ? null : (LocalDate) row[3];
            figures.put(p.productId(), new Figures(sellable, total, StockStatus.of(sellable, p.minStock()).code(),
                    StockStatus.belowReorder(sellable, p.reorderPoint()), nearest));
        }
        return figures;
    }

    private static BigDecimal quantity(Object value) {
        return value == null ? NONE : new BigDecimal(value.toString()).setScale(QUANTITY_SCALE);
    }
}
