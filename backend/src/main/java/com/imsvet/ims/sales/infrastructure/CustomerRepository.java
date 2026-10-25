package com.imsvet.ims.sales.infrastructure;

import com.imsvet.ims.sales.domain.Customer;
import com.imsvet.ims.sales.domain.CustomerType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Customers are never deleted (ADR-109); names compare ignoring case and accents (column collation). */
public interface CustomerRepository extends Repository<Customer, Long> {

    Customer saveAndFlush(Customer customer);

    Optional<Customer> findById(Long id);

    List<Customer> findAllByIdIn(Collection<Long> ids);

    /**
     * Every filter optional; {@code pattern} ({@code %term%} escaped with {@code !}) on the name or the phone,
     * {@code phoneKey} on the normalised phone, {@code taxKey} on the tax id; by name.
     */
    @Query("""
            SELECT c FROM Customer c
            WHERE (:pattern IS NULL OR c.name LIKE :pattern ESCAPE '!' OR c.phone LIKE :pattern ESCAPE '!'
                   OR (:phoneKey IS NOT NULL AND c.phoneKey = :phoneKey) OR (:taxKey IS NOT NULL AND c.taxId = :taxKey))
              AND (:type IS NULL OR c.type = :type)
              AND (:wilaya IS NULL OR c.wilaya = :wilaya)
              AND (:commune IS NULL OR c.commune = :commune)
              AND (:categoryId IS NULL OR c.categoryId = :categoryId)
              AND (:active IS NULL OR c.active = :active)
            ORDER BY c.name, c.id""")
    Page<Customer> search(String pattern, String phoneKey, String taxKey, CustomerType type, String wilaya,
            String commune, Integer categoryId, Boolean active, Pageable pageable);

    /** Same name (ignoring case and accents) and same phone key: a hard duplicate (ADR-110). */
    @Query("SELECT c FROM Customer c WHERE c.name = :name AND c.phoneKey = :phoneKey")
    List<Customer> findByNameAndPhoneKey(String name, String phoneKey);

    Optional<Customer> findByTaxId(String taxId);

    @Query("SELECT c FROM Customer c WHERE c.name = :name")
    List<Customer> findByName(String name);

    List<Customer> findByPhoneKey(String phoneKey);

    boolean existsByVeterinarianId(Long veterinarianId);
}
