package com.imsvet.ims.purchasing.application;

import com.imsvet.ims.purchasing.application.OrderViews.ProductRef;
import com.imsvet.ims.purchasing.application.OrderViews.SupplierRef;
import com.imsvet.ims.purchasing.application.OrderViews.UserRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** api.md §4.8 and §5.4 goods-receipt DTOs. */
public final class ReceiptViews {

    public record ReceiptOrder(long id, String number, String status) {
    }

    public record ReceiptLine(long id, Long poLineId, ProductRef product, long lotId, String lotNumber,
            LocalDate expiryDate, BigDecimal quantityBase, BigDecimal unitCost, boolean conforming, boolean lotBlocked,
            long movementId) {
    }

    public record ReceiptSummary(long id, String number, SupplierRef supplier, ReceiptOrder purchaseOrder,
            Instant receivedAt, String status) {
    }

    public record ReceiptDetail(long id, String number, String status, SupplierRef supplier, ReceiptOrder purchaseOrder,
            String supplierDocRef, String notes, Instant receivedAt, UserRef receivedBy, List<ReceiptLine> lines,
            String cancelReason, UserRef cancelledBy, Instant cancelledAt) {
    }

    private ReceiptViews() {
    }
}
