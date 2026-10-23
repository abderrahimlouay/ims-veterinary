package com.imsvet.ims.inventory.infrastructure;

import com.imsvet.ims.inventory.domain.MovementType;
import com.imsvet.ims.inventory.domain.StockMovement;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Reads the ledger (FR-STOCK-002); every filter optional, newest first. */
public interface MovementReadRepository extends Repository<StockMovement, Long> {

    Optional<StockMovement> findById(Long id);

    /** The lot's last movement id (a count line's watermark, ADR-072), or null when it has none. */
    @Query("SELECT MAX(m.id) FROM StockMovement m WHERE m.lotId = :lotId")
    Long lastIdOfLot(long lotId);

    /** {@code from} inclusive, {@code to} exclusive. */
    @Query("""
            SELECT m FROM StockMovement m
            WHERE (:productId IS NULL OR m.productId = :productId)
              AND (:lotId IS NULL OR m.lotId = :lotId)
              AND (:type IS NULL OR m.type = :type)
              AND (:userId IS NULL OR m.userId = :userId)
              AND (:from IS NULL OR m.createdAt >= :from)
              AND (:to IS NULL OR m.createdAt < :to)
            ORDER BY m.id DESC""")
    Page<StockMovement> search(Long productId, Long lotId, MovementType type, Long userId, Instant from, Instant to,
            Pageable pageable);
}
