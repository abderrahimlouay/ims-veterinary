package com.imsvet.ims.catalog.application;

import com.imsvet.ims.catalog.application.ProductStock.StockFilter;
import com.imsvet.ims.catalog.application.ProductViews.ProductIngredientView;
import com.imsvet.ims.catalog.application.ProductViews.ProductSummary;
import com.imsvet.ims.catalog.domain.ActiveIngredient;
import com.imsvet.ims.catalog.domain.Product;
import com.imsvet.ims.catalog.domain.ProductIngredient;
import com.imsvet.ims.catalog.infrastructure.ActiveIngredientRepository;
import com.imsvet.ims.catalog.infrastructure.ProductRepository;
import com.imsvet.ims.catalog.infrastructure.ProductSearchRepository;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Active ingredients of a product and substitutes sharing one (FR-PROD-004). Substitutes are the products with
 * sellable stock, filtered in the database (ADR-089, ADR-090), with their stock figures.
 */
@Service
public class ProductIngredients {

    public record IngredientLine(Integer ingredientId, String strength) {
    }

    private static final int MAX_SUBSTITUTES = 50;

    private final ProductRepository products;
    private final ActiveIngredientRepository ingredients;
    private final ProductSearchRepository search;
    private final ProductStock stock;

    public ProductIngredients(ProductRepository products, ActiveIngredientRepository ingredients,
            ProductSearchRepository search, ProductStock stock) {
        this.products = products;
        this.ingredients = ingredients;
        this.search = search;
        this.stock = stock;
    }

    @PreAuthorize("hasAuthority('product.update')")
    @Transactional
    public List<ProductIngredientView> replace(long productId, List<IngredientLine> lines) {
        Product product = find(productId);
        List<Violation> violations = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        List<ProductIngredient> resolved = new ArrayList<>();
        for (IngredientLine line : lines) {
            ActiveIngredient ingredient = line.ingredientId() == null ? null
                    : ingredients.findById(line.ingredientId()).orElse(null);
            String strength = line.strength() == null || line.strength().isBlank() ? null : line.strength().trim();
            if (ingredient == null) {
                violations.add(new Violation("ingredients", "UNKNOWN", "Unknown active ingredient."));
            } else if (!seen.add(ingredient.id())) {
                violations.add(new Violation("ingredients", "DUPLICATE", "An ingredient is listed twice."));
            } else if (strength != null && strength.length() > ProductIngredient.STRENGTH_MAX_LENGTH) {
                violations.add(new Violation("ingredients", "SIZE", "The strength is too long."));
            } else {
                resolved.add(new ProductIngredient(ingredient, strength));
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException("The ingredient list is invalid.", violations);
        }
        product.replaceIngredients(resolved);
        products.flush();
        return ProductViews.ingredients(product);
    }

    @PreAuthorize("hasAuthority('product.read')")
    @Transactional(readOnly = true)
    public List<ProductSummary> substitutes(long productId) {
        Product product = find(productId);
        List<Integer> ingredientIds = product.ingredients().stream().map(i -> i.ingredient().id()).toList();
        if (ingredientIds.isEmpty()) {
            return List.of();
        }
        return ProductViews.summaries(search.substitutes(ingredientIds, product.id(),
                List.of(stock.condition(StockFilter.HAS_SELLABLE)), MAX_SUBSTITUTES), stock);
    }

    private Product find(long id) {
        return products.findById(id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Product not found.", Map.of("id", id)));
    }
}
