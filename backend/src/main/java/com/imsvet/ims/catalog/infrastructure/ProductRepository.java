package com.imsvet.ims.catalog.infrastructure;

import com.imsvet.ims.catalog.domain.Product;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
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

    /** Archived products included. */
    @Query("SELECT p.id FROM Product p WHERE p.category.id = :categoryId")
    List<Long> findIdsByCategoryId(int categoryId);

    /** Empty when the product has no category or does not exist. */
    @Query("SELECT p.category.id FROM Product p WHERE p.id = :id AND p.category IS NOT NULL")
    Optional<Integer> findCategoryIdById(long id);

    /** Case- and accent-insensitive through the column collation (ADR-051). */
    Optional<Product> findBySku(String sku);
}
