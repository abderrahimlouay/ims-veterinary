package com.imsvet.ims.catalog.infrastructure;

import com.imsvet.ims.catalog.application.ProductStock.Condition;
import com.imsvet.ims.catalog.domain.Product;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * Product searches that may carry stock conditions from {@code ProductStock} (ADR-090). The query text is built only
 * from the constants below and the constant condition texts; every value — search pattern, ids, dates — is a bind
 * parameter (no search term is ever concatenated).
 */
@Repository
public class ProductSearchRepository {

    /** The criteria of ADR-047/048; {@code pattern}/{@code prefix} are escaped with {@code !}; all optional. */
    public record Criteria(String term, String pattern, String prefix, Integer categoryId, Integer manufacturerId,
            Integer ingredientId, Integer speciesId, String barcode, boolean includeArchived) {
    }

    private static final String WHERE = """
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
              AND (:barcode IS NULL OR p.id IN (SELECT b.product.id FROM ProductBarcode b WHERE b.code = :barcode))""";

    private static final String SELECT = "SELECT p FROM Product p LEFT JOIN FETCH p.category";

    private static final String COUNT = "SELECT COUNT(p) FROM Product p";

    /** Exact SKU, then names starting with the term, then the others, by name (ADR-048). */
    private static final String ORDER = """
             ORDER BY CASE WHEN p.sku = :term THEN 0 WHEN p.commercialName LIKE :prefix ESCAPE '!' THEN 1 ELSE 2 END,
                     p.commercialName, p.id""";

    private static final String SUBSTITUTES = """
            SELECT p FROM Product p JOIN p.ingredients i
            WHERE i.ingredient.id IN :ingredientIds AND p.id <> :productId AND p.archived = false""";

    private static final String SUBSTITUTES_ORDER = """
             GROUP BY p ORDER BY COUNT(i.ingredient.id) DESC, p.commercialName, p.id""";

    private final EntityManager entityManager;

    public ProductSearchRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public List<Product> search(Criteria c, List<Condition> conditions, Pageable page) {
        TypedQuery<Product> query = entityManager.createQuery(SELECT + " " + WHERE + and(conditions) + " " + ORDER,
                Product.class);
        Map<String, Object> parameters = whereParameters(c, conditions);
        parameters.put("term", c.term());
        parameters.put("prefix", c.prefix());
        parameters.forEach(query::setParameter);
        return query.setFirstResult((int) page.getOffset()).setMaxResults(page.getPageSize()).getResultList();
    }

    public long count(Criteria c, List<Condition> conditions) {
        TypedQuery<Long> query = entityManager.createQuery(COUNT + " " + WHERE + and(conditions), Long.class);
        whereParameters(c, conditions).forEach(query::setParameter);
        return query.getSingleResult();
    }

    /** Other active products sharing an ingredient, most shared first (FR-PROD-004), filtered by the conditions. */
    public List<Product> substitutes(Collection<Integer> ingredientIds, long productId, List<Condition> conditions,
            int limit) {
        TypedQuery<Product> query = entityManager.createQuery(SUBSTITUTES + and(conditions) + " " + SUBSTITUTES_ORDER,
                Product.class);
        query.setParameter("ingredientIds", ingredientIds);
        query.setParameter("productId", productId);
        conditions.forEach(c -> c.parameters().forEach(query::setParameter));
        return query.setMaxResults(limit).getResultList();
    }

    /** Only the constant texts of the conditions: never a value. */
    static String and(List<Condition> conditions) {
        StringBuilder text = new StringBuilder();
        for (Condition condition : conditions) {
            text.append(" AND (").append(condition.jpql()).append(')');
        }
        return text.toString();
    }

    private static Map<String, Object> whereParameters(Criteria c, List<Condition> conditions) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("includeArchived", c.includeArchived());
        parameters.put("categoryId", c.categoryId());
        parameters.put("manufacturerId", c.manufacturerId());
        parameters.put("ingredientId", c.ingredientId());
        parameters.put("speciesId", c.speciesId());
        parameters.put("pattern", c.pattern());
        parameters.put("barcode", c.barcode());
        conditions.forEach(condition -> parameters.putAll(condition.parameters()));
        return parameters;
    }
}
