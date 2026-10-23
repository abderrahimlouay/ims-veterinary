package com.imsvet.ims.catalog.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;

/**
 * Port defined by the catalog and implemented by {@code inventory} (ADR-090, ADR-089): stock figures for products,
 * and the conditions that let product searches filter by stock inside the database. A condition is constant JPQL
 * text on the product alias {@code p} with bind parameters only — never a concatenated value — so the catalog can
 * add it to its queries without knowing how stock is stored.
 */
public interface ProductStock {

    /** {@code HAS_SELLABLE}: at least one sellable lot (BR-STOCK-011); the others: the status of BR-STOCK-013. */
    enum StockFilter {
        HAS_SELLABLE, IN_STOCK, LOW, OUT, BELOW_REORDER
    }

    /** A boolean JPQL expression on {@code p} and the values of its named parameters (all prefixed {@code stock}). */
    record Condition(String jpql, Map<String, Object> parameters) {
    }

    /** What the figures need from the catalog for one product. */
    record ProductThresholds(long productId, BigDecimal minStock, BigDecimal reorderPoint) {
    }

    /** Base-unit quantities (3 decimals), status {@code in_stock|low|out}, nearest expiry of a sellable lot or null. */
    record Figures(BigDecimal sellable, BigDecimal total, String status, boolean belowReorder,
            LocalDate nearestSellableExpiry) {
    }

    Condition condition(StockFilter filter);

    /** One entry per product given, including those without any lot. */
    Map<Long, Figures> figures(Collection<ProductThresholds> products);
}
