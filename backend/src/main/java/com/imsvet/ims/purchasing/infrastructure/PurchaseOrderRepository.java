package com.imsvet.ims.purchasing.infrastructure;

import com.imsvet.ims.purchasing.domain.OrderStatus;
import com.imsvet.ims.purchasing.domain.PurchaseOrder;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Purchase orders are cancelled, never deleted (ADR-097: a number is never reused). */
public interface PurchaseOrderRepository extends Repository<PurchaseOrder, Long> {

    PurchaseOrder saveAndFlush(PurchaseOrder order);

    void flush();

    Optional<PurchaseOrder> findById(Long id);

    /** Every filter optional; {@code to} inclusive; newest first. */
    @Query("""
            SELECT o FROM PurchaseOrder o
            WHERE (:status IS NULL OR o.status = :status)
              AND (:supplierId IS NULL OR o.supplierId = :supplierId)
              AND (:from IS NULL OR o.orderDate >= :from)
              AND (:to IS NULL OR o.orderDate <= :to)
            ORDER BY o.id DESC""")
    Page<PurchaseOrder> search(OrderStatus status, Long supplierId, LocalDate from, LocalDate to, Pageable pageable);
}
