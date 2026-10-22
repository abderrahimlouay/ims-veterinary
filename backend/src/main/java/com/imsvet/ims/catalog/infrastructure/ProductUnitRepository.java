package com.imsvet.ims.catalog.infrastructure;

import com.imsvet.ims.catalog.domain.ProductUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Packaging units; no delete in Phase 3 (a unit may later be referenced by document lines, BR-UNIT-004). */
public interface ProductUnitRepository extends Repository<ProductUnit, Long> {

    ProductUnit saveAndFlush(ProductUnit unit);

    @Query("SELECT u FROM ProductUnit u WHERE u.id = :id AND u.product.id = :productId")
    Optional<ProductUnit> findByIdAndProductId(long id, long productId);

    /** Case- and accent-insensitive through the column collation, as the unique key. */
    @Query("SELECT u FROM ProductUnit u WHERE u.product.id = :productId AND u.name = :name")
    Optional<ProductUnit> findByProductIdAndName(long productId, String name);

    @Query("SELECT u FROM ProductUnit u JOIN FETCH u.product WHERE u.product.id = :productId "
            + "ORDER BY u.factorToBase, u.name")
    List<ProductUnit> findByProductId(long productId);
}
