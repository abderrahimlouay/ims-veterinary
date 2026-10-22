package com.imsvet.ims.catalog.application;

import com.imsvet.ims.catalog.domain.Product;
import com.imsvet.ims.catalog.infrastructure.ProductRepository;
import com.imsvet.ims.common.security.InternalService;
import java.util.Collection;
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
    public record ProductRef(long id, String sku, String commercialName, String baseUnit, boolean divisible) {
    }

    private final ProductRepository products;

    public ProductLookup(ProductRepository products) {
        this.products = products;
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

    private static ProductRef ref(Product p) {
        return new ProductRef(p.id(), p.sku(), p.commercialName(), p.baseUnit(), p.divisible());
    }
}
