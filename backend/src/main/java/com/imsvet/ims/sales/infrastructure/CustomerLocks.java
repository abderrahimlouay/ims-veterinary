package com.imsvet.ims.sales.infrastructure;

import com.imsvet.ims.sales.domain.Customer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Locks a customer row ({@code SELECT … FOR UPDATE}) and reloads it: the credit-limit change here, the credit check
 * of a sale from Phase 7 (ADR-108, last in the lock order of database.md §7). A repository, so a lock wait timeout
 * becomes a concurrency failure.
 */
@Repository
public class CustomerLocks {

    private final EntityManager entityManager;

    CustomerLocks(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Optional<Customer> lock(long id) {
        entityManager.flush();
        Customer customer = entityManager.find(Customer.class, id);
        if (customer == null) {
            return Optional.empty();
        }
        entityManager.refresh(customer, LockModeType.PESSIMISTIC_WRITE);
        return Optional.of(customer);
    }
}
