package com.imsvet.ims.catalog.application;

import com.imsvet.ims.catalog.application.ProductViews.ProductIngredientView;
import com.imsvet.ims.catalog.application.ProductViews.ProductSummary;
import com.imsvet.ims.catalog.domain.ActiveIngredient;
import com.imsvet.ims.catalog.domain.Product;
import com.imsvet.ims.catalog.domain.ProductIngredient;
import com.imsvet.ims.catalog.infrastructure.ActiveIngredientRepository;
import com.imsvet.ims.catalog.infrastructure.ProductRepository;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Active ingredients of a product and substitutes sharing one (FR-PROD-004). Substitutes are not yet
 * filtered by sellable stock: stock exists from Phase 4.
 */
@Service
public class ProductIngredients {

    public record IngredientLine(Integer ingredientId, String strength) {
    }

    private static final int MAX_SUBSTITUTES = 50;

    private final ProductRepository products;
    private final ActiveIngredientRepository ingredients;

    public ProductIngredients(ProductRepository products, ActiveIngredientRepository ingredients) {
        this.products = products;
        this.ingredients = ingredients;
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
        return products.findSubstitutes(ingredientIds, product.id(), PageRequest.of(0, MAX_SUBSTITUTES)).stream()
                .map(ProductViews::summary).toList();
    }

    private Product find(long id) {
        return products.findById(id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Product not found.", Map.of("id", id)));
    }
}
