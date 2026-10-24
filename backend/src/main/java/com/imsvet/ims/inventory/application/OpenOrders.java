package com.imsvet.ims.inventory.application;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

/**
 * What is ordered and not yet received, per product in base units, on validated, sent and partially received
 * purchase orders (drafts not counted, ADR-102). Defined here and implemented by {@code purchasing}, so the reorder
 * generation subtracts open orders without inventory depending on purchasing (as ADR-090). Products with nothing
 * on order are absent.
 */
public interface OpenOrders {

    Map<Long, BigDecimal> outstandingBase(Collection<Long> productIds);
}
