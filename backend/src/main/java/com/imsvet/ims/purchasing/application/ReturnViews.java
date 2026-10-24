package com.imsvet.ims.purchasing.application;

import com.imsvet.ims.purchasing.application.OrderViews.ProductRef;
import com.imsvet.ims.purchasing.application.OrderViews.SupplierRef;
import com.imsvet.ims.purchasing.application.OrderViews.UserRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** api.md §4.11 supplier-return DTOs. */
public final class ReturnViews {

    public record ReturnLine(long id, ProductRef product, long lotId, String lotNumber, LocalDate expiryDate,
            BigDecimal quantityBase, long movementId) {
    }

    public record SupplierReturnSummary(long id, String number, SupplierRef supplier, String reason,
            Instant createdAt) {
    }

    public record SupplierReturnDetail(long id, String number, SupplierRef supplier, String reason, Instant createdAt,
            UserRef createdBy, List<ReturnLine> lines) {
    }

    private ReturnViews() {
    }
}
