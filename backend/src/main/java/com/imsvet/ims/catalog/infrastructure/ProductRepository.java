package com.imsvet.ims.catalog.infrastructure;

import com.imsvet.ims.catalog.domain.Product;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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

    List<Product> findAllByIdIn(Collection<Long> ids);

    /** Case- and accent-insensitive through the column collation (ADR-051). */
    Optional<Product> findBySku(String sku);
}
