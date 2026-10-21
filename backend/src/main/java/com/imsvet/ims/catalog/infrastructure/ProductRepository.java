package com.imsvet.ims.catalog.infrastructure;

import com.imsvet.ims.catalog.domain.Product;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /** Case- and accent-insensitive through the column collation (ADR-051). */
    Optional<Product> findBySku(String sku);

    @EntityGraph(attributePaths = "category")
    Page<Product> findAll(Pageable pageable);

    @EntityGraph(attributePaths = "category")
    Page<Product> findByArchivedFalse(Pageable pageable);

    /** Other active products sharing at least one ingredient, most shared first (FR-PROD-004). */
    @Query("""
            SELECT p FROM Product p JOIN p.ingredients i
            WHERE i.ingredient.id IN :ingredientIds AND p.id <> :productId AND p.archived = false
            GROUP BY p ORDER BY COUNT(i.ingredient.id) DESC, p.commercialName, p.id""")
    List<Product> findSubstitutes(Collection<Integer> ingredientIds, Long productId, Pageable limit);
}
