package com.imsvet.ims.inventory.infrastructure;

import com.imsvet.ims.inventory.domain.CountStatus;
import com.imsvet.ims.inventory.domain.CountType;
import com.imsvet.ims.inventory.domain.InventoryCount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * Count sessions; never deleted. A change of a count's lines reads the count {@code FOR SHARE} and cancelling or
 * regularising locks it {@code FOR UPDATE}, always before any lot (ADR-012), so no line changes on a count being
 * closed.
 */
public interface InventoryCountRepository extends Repository<InventoryCount, Long> {

    InventoryCount saveAndFlush(InventoryCount count);

    Optional<InventoryCount> findById(Long id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT c FROM InventoryCount c WHERE c.id = :id")
    Optional<InventoryCount> findByIdForShare(long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM InventoryCount c WHERE c.id = :id")
    Optional<InventoryCount> findByIdForUpdate(long id);

    boolean existsByTypeAndStatus(CountType type, CountStatus status);

    /** {@code status} optional; newest first. */
    @Query("SELECT c FROM InventoryCount c WHERE (:status IS NULL OR c.status = :status) ORDER BY c.id DESC")
    Page<InventoryCount> search(CountStatus status, Pageable pageable);
}
