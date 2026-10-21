package com.imsvet.ims.catalog.application;

import com.imsvet.ims.catalog.domain.Product;
import com.imsvet.ims.catalog.domain.ReferenceEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Read models of products (api.md §4.3). Decimals travel as strings (ADR-021): prices with 4 decimals,
 * quantities with 3, VAT with 2.
 */
public final class ProductViews {

    private ProductViews() {
    }

    public record ProductSummary(long id, String sku, String commercialName, ReferenceItemView category,
            String baseUnit, BigDecimal salePrice, boolean archived) {
    }

    public record ProductDetail(long id, String sku, String commercialName, ReferenceItemView category,
            ReferenceItemView dosageForm, ReferenceItemView manufacturer, String baseUnit, boolean divisible,
            BigDecimal vatRate, BigDecimal purchasePrice, BigDecimal salePrice, BigDecimal minStock,
            BigDecimal maxStock, BigDecimal reorderPoint, boolean archived, List<ProductIngredientView> ingredients,
            Instant createdAt, Instant updatedAt, long version) {
    }

    public record ProductIngredientView(ReferenceItemView ingredient, String strength) {
    }

    static ProductSummary summary(Product p) {
        return new ProductSummary(p.id(), p.sku(), p.commercialName(), ref(p.category()), p.baseUnit(),
                p.salePrice(), p.archived());
    }

    static ProductDetail detail(Product p) {
        return new ProductDetail(p.id(), p.sku(), p.commercialName(), ref(p.category()), ref(p.dosageForm()),
                ref(p.manufacturer()), p.baseUnit(), p.divisible(), p.vatRate(), p.purchasePrice(), p.salePrice(),
                p.minStock(), p.maxStock(), p.reorderPoint(), p.archived(), ingredients(p), p.createdAt(),
                p.updatedAt(), p.version());
    }

    /** Sorted by ingredient name. */
    static List<ProductIngredientView> ingredients(Product p) {
        return p.ingredients().stream()
                .map(i -> new ProductIngredientView(ref(i.ingredient()), i.strength()))
                .sorted(Comparator.comparing(v -> v.ingredient().name()))
                .toList();
    }

    private static ReferenceItemView ref(ReferenceEntry e) {
        return e == null ? null : new ReferenceItemView(e.id(), e.name(), e.code(), e.country());
    }
}
