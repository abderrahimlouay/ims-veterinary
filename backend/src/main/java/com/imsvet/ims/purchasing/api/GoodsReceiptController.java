package com.imsvet.ims.purchasing.api;

import com.imsvet.ims.common.idempotency.IdempotencyKeys;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.purchasing.application.GoodsReceipts;
import com.imsvet.ims.purchasing.application.ReceiptViews.ReceiptDetail;
import com.imsvet.ims.purchasing.application.ReceiptViews.ReceiptSummary;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * api.md §4.8 and §5.4 {@code /api/goods-receipts}; permissions on the service. Lot, cost, totals and base
 * quantities are computed by the server (BR-SALE-008); the {@code Idempotency-Key} header is optional (ADR-104).
 */
@RestController
@RequestMapping("/api/goods-receipts")
class GoodsReceiptController {

    record LineRequest(Long poLineId, Long productId, Long unitId, BigDecimal quantity, String lotNumber,
            LocalDate expiryDate, BigDecimal unitCost, Boolean conforming) {
    }

    record ReceiptRequest(Long supplierId, Long purchaseOrderId, String supplierDocRef, String notes,
            Boolean confirmLotBlocking, List<LineRequest> lines) {

        GoodsReceipts.ReceiptInput input() {
            return new GoodsReceipts.ReceiptInput(supplierId, purchaseOrderId, supplierDocRef, notes,
                    Boolean.TRUE.equals(confirmLotBlocking), lines == null ? null : lines.stream()
                            .map(l -> new GoodsReceipts.ReceivedLine(l.poLineId(), l.productId(), l.unitId(),
                                    l.quantity(), l.lotNumber(), l.expiryDate(), l.unitCost(), l.conforming()))
                            .toList());
        }
    }

    record ReasonRequest(String reason) {
    }

    private final GoodsReceipts receipts;

    GoodsReceiptController(GoodsReceipts receipts) {
        this.receipts = receipts;
    }

    @GetMapping
    PageResponse<ReceiptSummary> search(@RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) Long purchaseOrderId, @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return receipts.search(supplierId, purchaseOrderId, status, from, to, page, size);
    }

    @PostMapping
    ResponseEntity<ReceiptDetail> confirm(@RequestBody ReceiptRequest body,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        ReceiptDetail created = receipts.confirm(body.input(), idempotencyKey);
        return ResponseEntity.created(URI.create("/api/goods-receipts/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    ReceiptDetail get(@PathVariable long id) {
        return receipts.get(id);
    }

    @PostMapping("/{id}/cancel")
    ReceiptDetail cancel(@PathVariable long id, @RequestBody(required = false) ReasonRequest body) {
        return receipts.cancel(id, body == null ? null : body.reason());
    }
}
