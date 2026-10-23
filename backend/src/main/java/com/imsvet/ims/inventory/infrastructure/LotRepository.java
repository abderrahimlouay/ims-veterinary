package com.imsvet.ims.inventory.infrastructure;

import com.imsvet.ims.inventory.domain.Lot;
import com.imsvet.ims.inventory.domain.LotStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Lots are read, locked and created here (find-or-create, BR-LOT-002); never deleted, identity never edited (BR-LOT-011). */
public interface LotRepository extends Repository<Lot, Long> {

    Optional<Lot> findById(Long id);

    /** Locking read ({@code SELECT … FOR UPDATE}) for every change of a lot (ADR-012). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM Lot l WHERE l.id = :id")
    Optional<Lot> findByIdForUpdate(long id);

    void flush();

    /** Locking read of several lots in ascending id order, so concurrent use cases never deadlock (ADR-012). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM Lot l WHERE l.id IN :ids ORDER BY l.id")
    List<Lot> findAllByIdInForUpdate(Collection<Long> ids);

    List<Lot> findAllByIdIn(Collection<Long> ids);

    /**
     * Creates the lot (balance 0, active) unless (product, lot number) exists, ignoring case and accents (ADR-070,
     * ADR-058). A concurrent creation of the same lot waits on the unique key and then does nothing. Returns 1 when
     * a row was inserted.
     * The update count cannot tell an insert from a duplicate (the driver reports found rows), so callers reset
     * {@link #resetLastInsertId()} before and read {@link #lastInsertId()} after, on the same connection.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO lots (product_id, lot_number, expiry_date, unit_cost, quantity_on_hand, status, received_at)
            VALUES (:productId, :lotNumber, :expiryDate, :unitCost, 0, 'active', :receivedAt)
            ON DUPLICATE KEY UPDATE id = id""")
    int insertIfAbsent(long productId, String lotNumber, LocalDate expiryDate, BigDecimal unitCost, Instant receivedAt);

    @Query(nativeQuery = true, value = "SELECT LAST_INSERT_ID(0)")
    long resetLastInsertId();

    /** The id inserted by this connection since the last reset, or 0. */
    @Query(nativeQuery = true, value = "SELECT LAST_INSERT_ID()")
    long lastInsertId();

    /** Locking read by identity (ADR-012, ADR-070). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM Lot l WHERE l.productId = :productId AND l.lotNumber = :lotNumber")
    Optional<Lot> findByIdentityForUpdate(long productId, String lotNumber);

    /**
     * Every filter optional. {@code stored}, {@code expired} and {@code withStock} together express an effective
     * status (ADR-059) against {@code today}; {@code hasStock} keeps lots with stock; {@code expiringBy} keeps lots
     * expiring on or before that date (expired ones included); {@code expiryFrom}/{@code expiryTo} bound the expiry
     * date of an alert level ({@code ExpiryPolicy.window}); {@code pattern} is {@code %term%} escaped with
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
              AND (:expiryFrom IS NULL OR l.expiryDate >= :expiryFrom)
              AND (:expiryTo IS NULL OR l.expiryDate <= :expiryTo)
              AND (:pattern IS NULL OR l.lotNumber LIKE :pattern ESCAPE '!')
            ORDER BY l.expiryDate, l.id""")
    Page<Lot> search(Long productId, LotStatus stored, Boolean expired, Boolean withStock, boolean hasStock,
            LocalDate expiringBy, LocalDate expiryFrom, LocalDate expiryTo, String pattern, LocalDate today,
            Pageable pageable);

    /** Lots with stock expiring on or before {@code limit}, expired ones included (W12). */
    @Query("SELECT l FROM Lot l WHERE l.quantityOnHand > 0 AND l.expiryDate <= :limit ORDER BY l.expiryDate, l.id")
    List<Lot> findWithStockExpiringBy(LocalDate limit);

    @Query("SELECT l FROM Lot l WHERE l.productId = :productId ORDER BY l.expiryDate, l.id")
    List<Lot> findByProductIdInFefoOrder(long productId);
}
