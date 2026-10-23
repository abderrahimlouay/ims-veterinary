package com.imsvet.ims.catalog.application;

import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.catalog.application.ProductStock.Condition;
import com.imsvet.ims.catalog.infrastructure.ProductSearchRepository;
import com.imsvet.ims.catalog.infrastructure.ProductSearchRepository.Criteria;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.common.security.InternalService;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read port for other modules that list products with the catalog's search (ADR-090): {@code inventory}'s stock
 * list filters by name, SKU and category here, adding its own stock conditions (constant text, bind parameters).
 * Callers check their own permission.
 */
@Service
@InternalService
public class ProductQueries {

    private final ProductSearchRepository search;

    public ProductQueries(ProductSearchRepository search) {
        this.search = search;
    }

    /** {@code q} as in the product search (ADR-048: name, SKU or ingredient, case- and accent-insensitive). */
    @Transactional(readOnly = true)
    public Page<ProductRef> find(String q, Integer categoryId, boolean includeArchived, List<Condition> conditions,
            int page, int size) {
        Pageable request = PageRequests.of(page, size, Sort.unsorted());
        String term = q == null || q.isBlank() ? null : q.trim();
        String escaped = term == null ? null : term.replaceAll("([!%_])", "!$1");
        Criteria criteria = new Criteria(term, escaped == null ? null : "%" + escaped + "%",
                escaped == null ? null : escaped + "%", categoryId, null, null, null, null, includeArchived);
        List<ProductRef> found = search.search(criteria, conditions, request).stream().map(ProductLookup::ref).toList();
        return new PageImpl<>(found, request, search.count(criteria, conditions));
    }
}
