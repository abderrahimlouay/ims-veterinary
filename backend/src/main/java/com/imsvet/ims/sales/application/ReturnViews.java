package com.imsvet.ims.sales.application;

import com.imsvet.ims.sales.application.SalesViews.CustomerRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** The DTOs of customer returns (api.md §5.8); entities never leave the service (ADR-019). */
public final class ReturnViews {

    public record DocumentRef(long id, String number, String docType) {
    }

    public record ProductItem(long id, String sku, String commercialName, String baseUnit) {
    }

    /** {@code status}: the lot's stored status. */
    public record LotItem(long id, String lotNumber, LocalDate expiryDate, String status) {
    }

    public record OriginalDocument(long id, String number, String docType, boolean creditSale, CustomerRef customer,
            Instant issuedAt) {
    }

    /** {@code restockRefusal} {@code null} when the line may go back into its lot (ADR-129). */
    public record PreviewLine(long saleLineId, ProductItem product, LotItem lot, BigDecimal soldBase,
            BigDecimal returnedBase, BigDecimal returnableBase, boolean restockAllowed, String restockRefusal) {
    }

    public record CreditLine(long saleLineId, BigDecimal quantityBase, BigDecimal lineHt, BigDecimal lineVat,
            BigDecimal lineTtc) {
    }

    public record CreditPreview(BigDecimal totalHt, BigDecimal totalVat, BigDecimal totalTtc, List<CreditLine> lines) {
    }

    /** {@code refundRequired}: an immediate sale, refunded in full by the return (ADR-128). */
    public record ReturnPreview(OriginalDocument document, List<PreviewLine> lines, CreditPreview creditNote,
            boolean refundRequired) {
    }

    public record CreditNoteRef(long id, String number, BigDecimal totalHt, BigDecimal totalVat, BigDecimal totalTtc) {
    }

    public record ReturnedLine(long id, long saleLineId, ProductItem product, LotItem lot, BigDecimal quantityBase,
            boolean sealed, String disposition, String discardReason, String discardNote, List<Long> movementIds) {
    }

    public record RefundItem(long id, String method, BigDecimal amount, String reference, Long cashSessionId) {
    }

    public record CustomerReturnDetail(long id, String number, DocumentRef document, CustomerRef customer,
            String reason, Instant createdAt, long createdBy, CreditNoteRef creditNote, List<ReturnedLine> lines,
            List<RefundItem> refunds) {
    }

    private ReturnViews() {
    }
}
