package com.imsvet.ims.finance.infrastructure;

import com.imsvet.ims.finance.domain.SupplierInvoice;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface SupplierInvoiceRepository extends Repository<SupplierInvoice, Long> {

    SupplierInvoice saveAndFlush(SupplierInvoice invoice);

    Optional<SupplierInvoice> findById(Long id);

    List<SupplierInvoice> findAllByIdIn(Collection<Long> ids);

    boolean existsBySupplierIdAndInvoiceNumber(Long supplierId, String invoiceNumber);

    /** The supplier's recorded invoices (or credit notes), by due date then id. */
    @Query("""
            SELECT i FROM SupplierInvoice i WHERE i.supplierId = :supplierId AND i.creditNote = :creditNote
              AND i.status = 'recorded'
            ORDER BY i.dueDate, i.id""")
    List<SupplierInvoice> recordedOf(long supplierId, boolean creditNote);

    /**
     * Every filter optional; {@code openOnly}: recorded documents with something left (an invoice not fully settled, a
     * credit note not fully applied). By due date, then id.
     */
    @Query("""
            SELECT i FROM SupplierInvoice i
            WHERE (:supplierId IS NULL OR i.supplierId = :supplierId)
              AND (:creditNote IS NULL OR i.creditNote = :creditNote)
              AND (:dueBefore IS NULL OR i.dueDate < :dueBefore)
              AND (:openOnly = FALSE OR (i.status = 'recorded' AND i.totalTtc > COALESCE(
                  (SELECT SUM(a.amount) FROM SupplierCreditAllocation a
                   WHERE a.invoiceId = i.id OR a.creditNoteId = i.id), 0) + COALESCE(
                  (SELECT SUM(pa.amount) FROM SupplierPaymentAllocation pa WHERE pa.invoiceId = i.id
                   AND NOT EXISTS (SELECT 1 FROM SupplierPayment r WHERE r.reversesPaymentId = pa.paymentId)), 0)))
            ORDER BY i.dueDate, i.id""")
    Page<SupplierInvoice> search(Long supplierId, Boolean creditNote, LocalDate dueBefore, boolean openOnly,
            Pageable pageable);
}
