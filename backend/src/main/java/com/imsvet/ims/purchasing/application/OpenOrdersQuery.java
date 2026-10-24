package com.imsvet.ims.purchasing.application;

import com.imsvet.ims.inventory.application.OpenOrders;
import com.imsvet.ims.purchasing.domain.OrderStatus;
import com.imsvet.ims.purchasing.infrastructure.OpenOrderLines;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Implements inventory's {@link OpenOrders}: validated, sent and partially received orders; drafts not counted. */
@Component
class OpenOrdersQuery implements OpenOrders {

    private static final List<OrderStatus> OPEN = List.of(OrderStatus.VALIDATED, OrderStatus.SENT,
            OrderStatus.PARTIALLY_RECEIVED);

    private final OpenOrderLines lines;

    OpenOrdersQuery(OpenOrderLines lines) {
        this.lines = lines;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> outstandingBase(Collection<Long> productIds) {
        return lines.outstanding(OPEN, productIds).stream().collect(Collectors.toMap(
                row -> ((Number) row[0]).longValue(), row -> (BigDecimal) row[1]));
    }
}
