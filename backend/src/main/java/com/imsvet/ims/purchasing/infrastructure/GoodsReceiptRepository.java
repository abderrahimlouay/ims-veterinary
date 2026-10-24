package com.imsvet.ims.purchasing.infrastructure;

import com.imsvet.ims.purchasing.domain.GoodsReceipt;
import com.imsvet.ims.purchasing.domain.ReceiptStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Receipts are created confirmed and cancelled, never deleted (ADR-097, ADR-103). */
public interface GoodsReceiptRepository extends Repository<GoodsReceipt, Long> {

    GoodsReceipt saveAndFlush(GoodsReceipt receipt);

    void flush();

    Optional<GoodsReceipt> findById(Long id);

    /** Every filter optional; {@code from} inclusive, {@code to} exclusive (instants of business days); newest first. */
    @Query("""
            SELECT r FROM GoodsReceipt r
            WHERE (:supplierId IS NULL OR r.supplierId = :supplierId)
              AND (:purchaseOrderId IS NULL OR r.purchaseOrderId = :purchaseOrderId)
              AND (:status IS NULL OR r.status = :status)
              AND (:from IS NULL OR r.receivedAt >= :from)
              AND (:to IS NULL OR r.receivedAt < :to)
            ORDER BY r.receivedAt DESC, r.id DESC""")
    Page<GoodsReceipt> search(Long supplierId, Long purchaseOrderId, ReceiptStatus status, Instant from, Instant to,
            Pageable pageable);

    @Query("SELECT r FROM GoodsReceipt r WHERE r.purchaseOrderId = :purchaseOrderId ORDER BY r.id")
    List<GoodsReceipt> findByPurchaseOrderId(long purchaseOrderId);
}
