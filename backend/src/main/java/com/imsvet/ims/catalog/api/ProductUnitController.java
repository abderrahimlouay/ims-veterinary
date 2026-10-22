package com.imsvet.ims.catalog.api;

import com.imsvet.ims.catalog.application.ProductUnits;
import com.imsvet.ims.catalog.application.ProductViews.UnitView;
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
import org.springframework.web.bind.annotation.RestController;

/** api.md §4.3 packaging units; permissions on the service. Decimals arrive as JSON strings (ADR-021). */
@RestController
@RequestMapping("/api/products/{productId}/units")
class ProductUnitController {

    /** {@code salePrice} omitted or {@code null}: derived price (ADR-063); {@code version} is required by PUT. */
    record UnitWriteRequest(@NotBlank @Size(max = 40) String name, @NotNull BigDecimal factorToBase,
            BigDecimal salePrice, @NotNull Boolean sellable, Long version) {

        ProductUnits.UnitInput input() {
            return new ProductUnits.UnitInput(name, factorToBase, salePrice, sellable);
        }
    }

    private final ProductUnits units;

    ProductUnitController(ProductUnits units) {
        this.units = units;
    }

    @GetMapping
    List<UnitView> list(@PathVariable long productId) {
        return units.list(productId);
    }

    @PostMapping
    ResponseEntity<UnitView> add(@PathVariable long productId, @Valid @RequestBody UnitWriteRequest body) {
        UnitView created = units.add(productId, body.input());
        return ResponseEntity.created(URI.create("/api/products/" + productId + "/units/" + created.id())).body(created);
    }

    @PutMapping("/{unitId}")
    UnitView update(@PathVariable long productId, @PathVariable long unitId, @Valid @RequestBody UnitWriteRequest body) {
        return units.update(productId, unitId, body.input(), body.version() == null ? -1 : body.version());
    }
}
