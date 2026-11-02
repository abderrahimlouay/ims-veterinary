package com.imsvet.ims.sales.infrastructure;

import com.imsvet.ims.sales.domain.CustomerReturnLine;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Insert and read only (BR-RET-007): no update or delete is exposed. */
public interface CustomerReturnLineRepository extends Repository<CustomerReturnLine, Long> {

    /** What the credit notes of earlier returns credited on a sale line (ADR-130). */
    interface Credited {
        BigDecimal getHt();

        BigDecimal getVat();
    }

    CustomerReturnLine saveAndFlush(CustomerReturnLine line);

    List<CustomerReturnLine> findByReturnIdOrderByIdAsc(Long returnId);

    @Query("""
            SELECT COALESCE(SUM(c.creditedHt), 0) AS ht, COALESCE(SUM(c.creditedVat), 0) AS vat
            FROM CustomerReturnLine r JOIN SalesDocumentLine c ON c.id = r.creditNoteLineId
            WHERE r.saleLineId = :saleLineId""")
    Credited creditedOn(Long saleLineId);
}
