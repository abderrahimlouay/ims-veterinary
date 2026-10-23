package com.imsvet.ims.inventory.application;

import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.catalog.application.ProductQueries;
import com.imsvet.ims.catalog.application.ProductStock.Condition;
import com.imsvet.ims.catalog.application.ProductStock.Figures;
import com.imsvet.ims.catalog.application.ProductStock.ProductThresholds;
import com.imsvet.ims.catalog.application.ProductStock.StockFilter;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.paging.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /api/inventory/stock} (api.md §4.5, ADR-080): products with total and sellable stock, thresholds,
 * status and nearest sellable expiry. Searching and paging run through the catalog's {@link ProductQueries} port with
 * this module's stock conditions, all inside the database (ADR-090).
 */
@Service
public class StockLevels {

    /** All optional; {@code status} is {@code in_stock}, {@code low} or {@code out}. */
    public record StockFilterInput(String q, Integer categoryId, String status, boolean belowReorder,
            boolean includeArchived) {
    }

    public record StockProduct(long id, String sku, String commercialName, String baseUnit, boolean archived) {
    }

    /** api.md §4.5 {@code StockLevel}. */
    public record StockLevel(StockProduct product, BigDecimal totalQty, BigDecimal sellableQty, BigDecimal minStock,
            BigDecimal maxStock, BigDecimal reorderPoint, String status, boolean belowReorder,
            LocalDate nearestSellableExpiry) {
    }

    private static final Map<String, StockFilter> STATUSES = Map.of("in_stock", StockFilter.IN_STOCK, "low",
            StockFilter.LOW, "out", StockFilter.OUT);

    private final ProductQueries products;
    private final ProductStockConditions stock;

    public StockLevels(ProductQueries products, ProductStockConditions stock) {
        this.products = products;
        this.stock = stock;
    }

    @PreAuthorize("hasAuthority('stock.read')")
    @Transactional(readOnly = true)
    public PageResponse<StockLevel> search(StockFilterInput in, int page, int size) {
        List<Condition> conditions = new ArrayList<>();
        if (in.status() != null && !in.status().isBlank()) {
            StockFilter filter = STATUSES.get(in.status());
            if (filter == null) {
                throw new ValidationException("The stock filter is invalid.",
                        List.of(new Violation("status", "UNKNOWN", "Unknown stock status.")));
            }
            conditions.add(stock.condition(filter));
        }
        if (in.belowReorder()) {
            conditions.add(stock.condition(StockFilter.BELOW_REORDER));
        }
        Page<ProductRef> found = products.find(in.q(), in.categoryId(), in.includeArchived(), conditions, page, size);
        Map<Long, Figures> figures = stock.figures(found.stream()
                .map(p -> new ProductThresholds(p.id(), p.minStock(), p.reorderPoint())).toList());
        return PageResponse.of(found, p -> level(p, figures.get(p.id())));
    }

    private static StockLevel level(ProductRef p, Figures f) {
        return new StockLevel(new StockProduct(p.id(), p.sku(), p.commercialName(), p.baseUnit(), p.archived()),
                f.total(), f.sellable(), p.minStock(), p.maxStock(), p.reorderPoint(), f.status(), f.belowReorder(),
                f.nearestSellableExpiry());
    }
}
