package com.imsvet.ims.catalog.application;

import com.imsvet.ims.catalog.domain.Product;
import com.imsvet.ims.catalog.domain.ProductUnit;
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
            List<ReferenceItemView> targetSpecies, List<WithdrawalPeriodView> withdrawalPeriods, Instant createdAt,
            Instant updatedAt, long version) {
    }

    /** Days are {@code null} when not specified (BR-PROD-007). */
    public record WithdrawalPeriodView(ReferenceItemView species, Integer meatDays, Integer milkDays,
            Integer eggsDays) {
    }

    public record ProductIngredientView(ReferenceItemView ingredient, String strength) {
    }

    /** ADR-063: {@code salePrice} is the stored price or {@code null}; {@code effectiveSalePrice} is never null. */
    public record UnitView(long id, String name, BigDecimal factorToBase, BigDecimal salePrice,
            BigDecimal effectiveSalePrice, boolean sellable, long version) {
    }

    static UnitView unit(ProductUnit u) {
        return new UnitView(u.id(), u.name(), u.factorToBase(), u.salePrice(), u.effectiveSalePrice(), u.sellable(),
                u.version());
    }

    static ProductSummary summary(Product p) {
        return new ProductSummary(p.id(), p.sku(), p.commercialName(), ref(p.category()), p.baseUnit(),
                p.salePrice(), p.archived());
    }

    static ProductDetail detail(Product p) {
        return new ProductDetail(p.id(), p.sku(), p.commercialName(), ref(p.category()), ref(p.dosageForm()),
                ref(p.manufacturer()), p.baseUnit(), p.divisible(), p.vatRate(), p.purchasePrice(), p.salePrice(),
                p.minStock(), p.maxStock(), p.reorderPoint(), p.archived(), ingredients(p), targetSpecies(p),
                withdrawalPeriods(p), p.createdAt(), p.updatedAt(), p.version());
    }

    /** Sorted by species name. */
    static List<ReferenceItemView> targetSpecies(Product p) {
        return p.targetSpecies().stream().map(ProductViews::ref)
                .sorted(Comparator.comparing(ReferenceItemView::name)).toList();
    }

    /** Sorted by species name. */
    static List<WithdrawalPeriodView> withdrawalPeriods(Product p) {
        return p.withdrawalPeriods().stream()
                .map(w -> new WithdrawalPeriodView(ref(w.species()), days(w.meatDays()), days(w.milkDays()),
                        days(w.eggsDays())))
                .sorted(Comparator.comparing(v -> v.species().name()))
                .toList();
    }

    private static Integer days(Short days) {
        return days == null ? null : days.intValue();
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
