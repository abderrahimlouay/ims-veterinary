package com.imsvet.ims.purchasing.infrastructure;

import com.imsvet.ims.purchasing.domain.OrderStatus;
import com.imsvet.ims.purchasing.domain.PurchaseOrderLine;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Ordered and not yet received base quantities per product on orders of the given statuses (ADR-102). */
public interface OpenOrderLines extends Repository<PurchaseOrderLine, Long> {

    /** Rows: product id, outstanding base quantity (> 0). */
    @Query("""
            SELECT l.productId, SUM(l.quantityBase - l.receivedBase) FROM PurchaseOrderLine l JOIN l.order o
            WHERE o.status IN :statuses AND l.productId IN :productIds
            GROUP BY l.productId HAVING SUM(l.quantityBase - l.receivedBase) > 0""")
    List<Object[]> outstanding(Collection<OrderStatus> statuses, Collection<Long> productIds);
}
