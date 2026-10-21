package com.imsvet.ims.catalog.infrastructure;

import com.imsvet.ims.catalog.domain.Product;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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


    /**
     * ADR-048 / ADR-047: every filter optional; {@code pattern} is {@code %term%} escaped with {@code !} and
     * {@code prefix} is {@code term%}. Order: exact SKU, name starting with the term, other matches, then name.
     */
    @Query("""
            SELECT p FROM Product p LEFT JOIN FETCH p.category
            WHERE (:includeArchived = true OR p.archived = false)
              AND (:categoryId IS NULL OR p.category.id = :categoryId)
              AND (:manufacturerId IS NULL OR p.manufacturer.id = :manufacturerId)
              AND (:ingredientId IS NULL OR p.id IN (
                   SELECT pi.id FROM Product pi JOIN pi.ingredients ii WHERE ii.ingredient.id = :ingredientId))
              AND (:speciesId IS NULL
                   OR p.id IN (SELECT pt.id FROM Product pt JOIN pt.targetSpecies ts WHERE ts.id = :speciesId)
                   OR p.id IN (SELECT pw.id FROM Product pw JOIN pw.withdrawalPeriods ww WHERE ww.species.id = :speciesId))
              AND (:pattern IS NULL OR p.commercialName LIKE :pattern ESCAPE '!' OR p.sku LIKE :pattern ESCAPE '!'
                   OR p.id IN (SELECT pq.id FROM Product pq JOIN pq.ingredients iq JOIN iq.ingredient aq
                               WHERE aq.name LIKE :pattern ESCAPE '!'))
            ORDER BY CASE WHEN p.sku = :term THEN 0 WHEN p.commercialName LIKE :prefix ESCAPE '!' THEN 1 ELSE 2 END,
                     p.commercialName, p.id""")
    List<Product> search(String term, String pattern, String prefix, Integer categoryId, Integer manufacturerId,
            Integer ingredientId, Integer speciesId, boolean includeArchived, Pageable pageable);

    @Query("""
            SELECT COUNT(p) FROM Product p
            WHERE (:includeArchived = true OR p.archived = false)
              AND (:categoryId IS NULL OR p.category.id = :categoryId)
              AND (:manufacturerId IS NULL OR p.manufacturer.id = :manufacturerId)
              AND (:ingredientId IS NULL OR p.id IN (
                   SELECT pi.id FROM Product pi JOIN pi.ingredients ii WHERE ii.ingredient.id = :ingredientId))
              AND (:speciesId IS NULL
                   OR p.id IN (SELECT pt.id FROM Product pt JOIN pt.targetSpecies ts WHERE ts.id = :speciesId)
                   OR p.id IN (SELECT pw.id FROM Product pw JOIN pw.withdrawalPeriods ww WHERE ww.species.id = :speciesId))
              AND (:pattern IS NULL OR p.commercialName LIKE :pattern ESCAPE '!' OR p.sku LIKE :pattern ESCAPE '!'
                   OR p.id IN (SELECT pq.id FROM Product pq JOIN pq.ingredients iq JOIN iq.ingredient aq
                               WHERE aq.name LIKE :pattern ESCAPE '!'))""")
    long countSearch(String pattern, Integer categoryId, Integer manufacturerId, Integer ingredientId,
            Integer speciesId, boolean includeArchived);

    /** Other active products sharing at least one ingredient, most shared first (FR-PROD-004). */
    @Query("""
            SELECT p FROM Product p JOIN p.ingredients i
            WHERE i.ingredient.id IN :ingredientIds AND p.id <> :productId AND p.archived = false
            GROUP BY p ORDER BY COUNT(i.ingredient.id) DESC, p.commercialName, p.id""")
    List<Product> findSubstitutes(Collection<Integer> ingredientIds, Long productId, Pageable limit);
}
