package com.imsvet.ims.purchasing.api;

import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.purchasing.application.OrderPrefill;
import com.imsvet.ims.purchasing.application.OrderViews.PricePrefill;
import com.imsvet.ims.purchasing.application.OrderViews.PurchaseOrderDetail;
import com.imsvet.ims.purchasing.application.OrderViews.PurchaseOrderSummary;
import com.imsvet.ims.purchasing.application.PurchaseOrders;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * api.md §4.7 {@code /api/purchase-orders}; permissions on the services. Amounts sent by a client (line or total)
 * are not part of the request records and therefore ignored (BR-PURCH-002).
 */
@RestController
@RequestMapping("/api/purchase-orders")
class PurchaseOrderController {

    record LineRequest(Long productId, Long unitId, BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPct,
            BigDecimal vatRate) {
    }

    /** {@code version} is required by PUT (ADR-027). */
    record OrderRequest(Long supplierId, LocalDate expectedDate, List<LineRequest> lines, Long version) {

        PurchaseOrders.OrderInput input() {
            return new PurchaseOrders.OrderInput(supplierId, expectedDate, lines == null ? null : lines.stream()
                    .map(l -> new PurchaseOrders.RequestedLine(l.productId(), l.unitId(), l.quantity(),
                            l.unitPrice(), l.discountPct(), l.vatRate()))
                    .toList());
        }
    }

    record ReasonRequest(String reason) {
    }

    private final PurchaseOrders orders;
    private final OrderPrefill prefill;

    PurchaseOrderController(PurchaseOrders orders, OrderPrefill prefill) {
        this.orders = orders;
        this.prefill = prefill;
    }

    @GetMapping
    PageResponse<PurchaseOrderSummary> search(@RequestParam(required = false) String status,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return orders.search(status, supplierId, from, to, page, size);
    }

    @PostMapping
    ResponseEntity<PurchaseOrderDetail> create(@RequestBody OrderRequest body) {
        PurchaseOrderDetail created = orders.create(body.input());
        return ResponseEntity.created(URI.create("/api/purchase-orders/" + created.id())).body(created);
    }

    @GetMapping("/prefill")
    PricePrefill prefill(@RequestParam long supplierId, @RequestParam long productId,
            @RequestParam(required = false) Long unitId) {
        return prefill.prefill(supplierId, productId, unitId);
    }

    @GetMapping("/{id}")
    PurchaseOrderDetail get(@PathVariable long id) {
        return orders.get(id);
    }

    @PutMapping("/{id}")
    PurchaseOrderDetail update(@PathVariable long id, @RequestBody OrderRequest body) {
        return orders.update(id, body.input(), body.version() == null ? -1 : body.version());
    }

    @PostMapping("/{id}/validate")
    PurchaseOrderDetail validate(@PathVariable long id) {
        return orders.validate(id);
    }

    @PostMapping("/{id}/send")
    PurchaseOrderDetail send(@PathVariable long id) {
        return orders.send(id);
    }

    @PostMapping("/{id}/cancel")
    PurchaseOrderDetail cancel(@PathVariable long id, @RequestBody(required = false) ReasonRequest body) {
        return orders.cancel(id, body == null ? null : body.reason());
    }

    @PostMapping("/{id}/close")
    PurchaseOrderDetail close(@PathVariable long id, @RequestBody(required = false) ReasonRequest body) {
        return orders.close(id, body == null ? null : body.reason());
    }
}
