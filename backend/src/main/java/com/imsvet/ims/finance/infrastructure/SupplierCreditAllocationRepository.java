package com.imsvet.ims.finance.infrastructure;

import com.imsvet.ims.finance.domain.SupplierCreditAllocation;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Insert only (BR-PAY-003): no update or delete is exposed. */
public interface SupplierCreditAllocationRepository extends Repository<SupplierCreditAllocation, Long> {

    SupplierCreditAllocation saveAndFlush(SupplierCreditAllocation allocation);

    List<SupplierCreditAllocation> findByInvoiceIdOrCreditNoteIdOrderByIdAsc(Long invoiceId, Long creditNoteId);

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM SupplierCreditAllocation a WHERE a.invoiceId = :invoiceId")
    BigDecimal creditedTo(long invoiceId);

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM SupplierCreditAllocation a WHERE a.creditNoteId = :creditNoteId")
    BigDecimal appliedFrom(long creditNoteId);
}
