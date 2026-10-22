package com.imsvet.ims.inventory.infrastructure;

import com.imsvet.ims.inventory.domain.Lot;
import com.imsvet.ims.inventory.domain.LotStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Lots are read here; no save or delete in Phase 3 (ADR-064, BR-LOT-011). */
public interface LotRepository extends Repository<Lot, Long> {

    Optional<Lot> findById(Long id);

    /**
     * Every filter optional. {@code stored}, {@code expired} and {@code withStock} together express an effective
     * status (ADR-059) against {@code today}; {@code hasStock} keeps lots with stock; {@code expiringBy} keeps lots
     * expiring on or before that date (expired ones included); {@code pattern} is {@code %term%} escaped with
     * {@code !} on the lot number. Order: expiry date, then id (FEFO).
     */
    @Query("""
            SELECT l FROM Lot l
            WHERE (:productId IS NULL OR l.productId = :productId)
              AND (:stored IS NULL OR l.status = :stored)
              AND (:expired IS NULL OR (:expired = true AND l.expiryDate < :today)
                   OR (:expired = false AND l.expiryDate >= :today))
              AND (:withStock IS NULL OR (:withStock = true AND l.quantityOnHand > 0)
                   OR (:withStock = false AND l.quantityOnHand = 0))
              AND (:hasStock = false OR l.quantityOnHand > 0)
              AND (:expiringBy IS NULL OR l.expiryDate <= :expiringBy)
              AND (:pattern IS NULL OR l.lotNumber LIKE :pattern ESCAPE '!')
            ORDER BY l.expiryDate, l.id""")
    Page<Lot> search(Long productId, LotStatus stored, Boolean expired, Boolean withStock, boolean hasStock,
            LocalDate expiringBy, String pattern, LocalDate today, Pageable pageable);

    @Query("SELECT l FROM Lot l WHERE l.productId = :productId ORDER BY l.expiryDate, l.id")
    List<Lot> findByProductIdInFefoOrder(long productId);
}
