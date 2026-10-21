package com.imsvet.ims.catalog.api;

import com.imsvet.ims.catalog.application.ProductCatalog;
import com.imsvet.ims.catalog.application.ProductCatalog.ProductInput;
import com.imsvet.ims.catalog.application.ProductIngredients;
import com.imsvet.ims.catalog.application.ProductSpecies;
import com.imsvet.ims.catalog.application.ReferenceItemView;
import com.imsvet.ims.catalog.application.ProductViews.ProductDetail;
import com.imsvet.ims.catalog.application.ProductViews.ProductIngredientView;
import com.imsvet.ims.catalog.application.ProductViews.ProductSummary;
import com.imsvet.ims.catalog.application.ProductViews.WithdrawalPeriodView;
import com.imsvet.ims.common.paging.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * api.md §4.3 products; permissions on the service. Decimals arrive as JSON strings (ADR-021).
 */
@RestController
@RequestMapping("/api/products")
class ProductController {

    /**
     * {@code ProductWriteRequest}; {@code version} is required by PUT (ADR-027); prices per base unit are
     * optional (0 at creation, unchanged on update when omitted).
     */
    record ProductWriteRequest(@NotBlank @Size(max = 40) String sku, @NotBlank @Size(max = 200) String commercialName,
            Integer categoryId, Integer dosageFormId, Integer manufacturerId, @NotBlank @Size(max = 20) String baseUnit,
            @NotNull Boolean divisible, BigDecimal vatRate, BigDecimal minStock, BigDecimal maxStock,
            BigDecimal reorderPoint, BigDecimal purchasePrice, BigDecimal salePrice, Long version) {

        ProductInput input() {
            return new ProductInput(sku, commercialName, categoryId, dosageFormId, manufacturerId, baseUnit, divisible,
                    vatRate, minStock, maxStock, reorderPoint, purchasePrice, salePrice);
        }
    }

    record IngredientLineRequest(@NotNull Integer ingredientId, String strength) {
    }

    record TargetSpeciesRequest(@NotNull List<@NotNull Integer> speciesIds) {
    }

    record WithdrawalPeriodRequest(@NotNull Integer speciesId, Integer meatDays, Integer milkDays, Integer eggsDays) {
    }

    private final ProductCatalog catalog;
    private final ProductIngredients ingredients;
    private final ProductSpecies species;

    ProductController(ProductCatalog catalog, ProductIngredients ingredients, ProductSpecies species) {
        this.catalog = catalog;
        this.ingredients = ingredients;
        this.species = species;
    }

    @GetMapping
    PageResponse<ProductSummary> list(@RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return catalog.list(includeArchived, page, size);
    }

    @PostMapping
    ResponseEntity<ProductDetail> create(@Valid @RequestBody ProductWriteRequest body) {
        ProductDetail created = catalog.create(body.input());
        return ResponseEntity.created(URI.create("/api/products/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    ProductDetail get(@PathVariable long id) {
        return catalog.get(id);
    }

    @PutMapping("/{id}")
    ProductDetail update(@PathVariable long id, @Valid @RequestBody ProductWriteRequest body) {
        return catalog.update(id, body.input(), body.version() == null ? -1 : body.version());
    }

    @PutMapping("/{id}/ingredients")
    List<ProductIngredientView> replaceIngredients(@PathVariable long id,
            @RequestBody List<@Valid IngredientLineRequest> body) {
        return ingredients.replace(id, body.stream()
                .map(l -> new ProductIngredients.IngredientLine(l.ingredientId(), l.strength())).toList());
    }

    @PutMapping("/{id}/target-species")
    List<ReferenceItemView> replaceTargetSpecies(@PathVariable long id, @Valid @RequestBody TargetSpeciesRequest body) {
        return species.replaceTargetSpecies(id, body.speciesIds());
    }

    @PutMapping("/{id}/withdrawal-periods")
    List<WithdrawalPeriodView> replaceWithdrawalPeriods(@PathVariable long id,
            @RequestBody List<@Valid WithdrawalPeriodRequest> body) {
        return species.replaceWithdrawalPeriods(id, body.stream()
                .map(p -> new ProductSpecies.PeriodLine(p.speciesId(), p.meatDays(), p.milkDays(), p.eggsDays()))
                .toList());
    }

    @GetMapping("/{id}/substitutes")
    List<ProductSummary> substitutes(@PathVariable long id) {
        return ingredients.substitutes(id);
    }

    @PostMapping("/{id}/archive")
    ProductDetail archive(@PathVariable long id) {
        return catalog.archive(id);
    }

    @PostMapping("/{id}/restore")
    ProductDetail restore(@PathVariable long id) {
        return catalog.restore(id);
    }
}
