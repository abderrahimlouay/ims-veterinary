package com.imsvet.ims.purchasing.infrastructure;

import com.imsvet.ims.purchasing.domain.Supplier;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Suppliers are deactivated, never deleted (BR-PURCH-011): no delete method. */
public interface SupplierRepository extends Repository<Supplier, Long> {

    Supplier saveAndFlush(Supplier supplier);

    Optional<Supplier> findById(Long id);

    /** Case- and accent-insensitive through the column collation (ADR-100). */
    Optional<Supplier> findByName(String name);

    /** {@code pattern} {@code %term%} escaped with {@code !}, or null; {@code active} optional; by name. */
    @Query("""
            SELECT s FROM Supplier s
            WHERE (:pattern IS NULL OR s.name LIKE :pattern ESCAPE '!')
              AND (:active IS NULL OR s.active = :active)
            ORDER BY s.name, s.id""")
    Page<Supplier> search(String pattern, Boolean active, Pageable pageable);
}
