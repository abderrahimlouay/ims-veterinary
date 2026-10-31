package com.imsvet.ims.inventory.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Who received a lot (BR-LOT-008, ADR-117). Defined here and implemented by {@code sales}, so a recall lists the buyers
 * without inventory depending on sales (as {@link OpenOrders}). Joins the caller's transaction.
 */
public interface LotBuyers {

    /** One document that took stock of the lot; {@code customerId} {@code null} on an anonymous sale. */
    record LotSale(Long customerId, String customerName, String phone, String documentNumber, String documentType,
            Instant issuedAt, BigDecimal quantityBase) {
    }

    /** Oldest first. */
    List<LotSale> salesOf(long lotId);
}
