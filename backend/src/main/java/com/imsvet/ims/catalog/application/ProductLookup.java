package com.imsvet.ims.catalog.application;

import com.imsvet.ims.catalog.domain.Product;
import com.imsvet.ims.catalog.infrastructure.ProductRepository;
import com.imsvet.ims.catalog.infrastructure.ProductUnitRepository;
import com.imsvet.ims.catalog.infrastructure.TherapeuticCategoryRepository;
import com.imsvet.ims.common.security.InternalService;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read port of the catalogue for other modules (backend.md §3): they never read catalog tables directly. Callers
 * check their own permission (archived products included).
 */
@Service
@InternalService
public class ProductLookup {

    /** What other modules need to show and handle a product. */
    public record ProductRef(long id, String sku, String commercialName, String baseUnit, boolean divisible,
            boolean archived, BigDecimal minStock, BigDecimal maxStock, BigDecimal reorderPoint) {
    }

    /** A packaging unit of a product (FR-PROD-006/007). */
    public record UnitRef(long id, String name, BigDecimal factorToBase, boolean sellable) {
    }

    private final ProductRepository products;
    private final ProductUnitRepository units;
    private final TherapeuticCategoryRepository categories;

    public ProductLookup(ProductRepository products, ProductUnitRepository units,
            TherapeuticCategoryRepository categories) {
        this.products = products;
        this.units = units;
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public boolean categoryExists(int categoryId) {
        return categories.findById(categoryId).isPresent();
    }

    /** The products of a therapeutic category, archived ones included (a cycle count's scope, ADR-073). */
    @Transactional(readOnly = true)
    public List<Long> idsInCategory(int categoryId) {
        return products.findIdsByCategoryId(categoryId);
    }

    @Transactional(readOnly = true)
    public Optional<Integer> categoryOf(long productId) {
        return products.findCategoryIdById(productId);
    }

    /** The unit only when it belongs to that product (BR-UNIT-005). */
    @Transactional(readOnly = true)
    public Optional<UnitRef> unit(long productId, long unitId) {
        return units.findByIdAndProductId(unitId, productId)
                .map(u -> new UnitRef(u.id(), u.name(), u.factorToBase(), u.sellable()));
    }

    @Transactional(readOnly = true)
    public Optional<ProductRef> find(long id) {
        return products.findById(id).map(ProductLookup::ref);
    }

    @Transactional(readOnly = true)
    public Map<Long, ProductRef> findAll(Collection<Long> ids) {
        return products.findAllByIdIn(ids).stream().map(ProductLookup::ref)
                .collect(Collectors.toMap(ProductRef::id, Function.identity()));
    }

    static ProductRef ref(Product p) {
        return new ProductRef(p.id(), p.sku(), p.commercialName(), p.baseUnit(), p.divisible(), p.archived(),
                p.minStock(), p.maxStock(), p.reorderPoint());
    }
}
