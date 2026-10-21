package com.imsvet.ims.catalog.infrastructure;

import com.imsvet.ims.catalog.domain.Product;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

/**
 * Products are archived, never deleted (BR-PROD-004, database.md §1): no delete method.
 */
public interface ProductRepository extends Repository<Product, Long> {

    Product saveAndFlush(Product product);

    void flush();

    @EntityGraph(attributePaths = {"category", "dosageForm", "manufacturer"})
    Optional<Product> findById(Long id);

    /** Case- and accent-insensitive through the column collation (ADR-051). */
    Optional<Product> findBySku(String sku);

    @EntityGraph(attributePaths = "category")
    Page<Product> findAll(Pageable pageable);

    @EntityGraph(attributePaths = "category")
    Page<Product> findByArchivedFalse(Pageable pageable);
}
