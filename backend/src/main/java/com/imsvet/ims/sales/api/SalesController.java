package com.imsvet.ims.sales.api;

import com.imsvet.ims.common.idempotency.IdempotencyKeys;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.sales.application.PaymentRecorder;
import com.imsvet.ims.sales.application.SaleCancellation;
import com.imsvet.ims.sales.application.SaleConfirmation;
import com.imsvet.ims.sales.application.SaleConversion;
import com.imsvet.ims.sales.application.SaleLines;
import com.imsvet.ims.sales.application.SalesDocuments;
import com.imsvet.ims.sales.application.SalesViews.SaleDetail;
import com.imsvet.ims.sales.application.SalesViews.SaleSummary;
import com.imsvet.ims.sales.application.SalesViews.SaleWarning;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** api.md §4.10 {@code /api/sales}; permissions on the services. */
@RestController
@RequestMapping("/api/sales")
class SalesController {

    /** {@code version} is required by PUT (ADR-027); the type of an existing document never changes. */
    record DraftRequest(String docType, Long customerId, Boolean creditSale, LocalDate dueDate, String notes,
            Long version) {

        SalesDocuments.DraftInput input() {
            return new SalesDocuments.DraftInput(docType, customerId, creditSale, dueDate, notes);
        }
    }

    /** Amounts and lots proposed by a client are not part of the request (BR-SALE-008, api.md §5.5). */
    record LineRequest(Long productId, Long unitId, BigDecimal quantity, Integer targetSpeciesId, Long lotId,
            String fefoOverrideReason, BigDecimal manualDiscountPct, BigDecimal manualUnitPrice) {

        SaleLines.LineInput input() {
            return new SaleLines.LineInput(productId, unitId, quantity, targetSpeciesId, lotId, fefoOverrideReason,
                    manualDiscountPct, manualUnitPrice);
        }
    }

    /** {@code amount} is what is kept; no tendered amount exists here (ADR-118). */
    record PaymentRequest(String method, BigDecimal amount, String reference) {
    }

    record ConfirmRequest(List<PaymentRequest> payments) {

        List<PaymentRecorder.Tender> tenders() {
            return payments == null ? List.of() : payments.stream()
                    .map(p -> new PaymentRecorder.Tender(p.method(), p.amount(), p.reference())).toList();
        }
    }

    record ConvertRequest(String targetType) {
    }

    record CancelRequest(String reason) {
    }

    private final SalesDocuments documents;
    private final SaleLines lines;
    private final SaleConfirmation confirmation;
    private final SaleConversion conversion;
    private final SaleCancellation cancellation;

    SalesController(SalesDocuments documents, SaleLines lines, SaleConfirmation confirmation,
            SaleConversion conversion, SaleCancellation cancellation) {
        this.documents = documents;
        this.lines = lines;
        this.confirmation = confirmation;
        this.conversion = conversion;
        this.cancellation = cancellation;
    }

    @GetMapping
    PageResponse<SaleSummary> search(@RequestParam(required = false) String docType,
            @RequestParam(required = false) String status, @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String number,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return documents.search(docType, status, customerId, number, from, to, page, size);
    }

    @PostMapping
    ResponseEntity<SaleDetail> create(@RequestBody DraftRequest body) {
        SaleDetail created = documents.create(body.input());
        return ResponseEntity.created(URI.create("/api/sales/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    SaleDetail get(@PathVariable long id) {
        return documents.get(id);
    }

    @PutMapping("/{id}")
    SaleDetail update(@PathVariable long id, @RequestBody DraftRequest body) {
        return documents.update(id, body.input(), body.version() == null ? -1 : body.version());
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> abandon(@PathVariable long id) {
        documents.abandon(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/lines")
    SaleDetail addLine(@PathVariable long id, @RequestBody LineRequest body) {
        return lines.add(id, body.input());
    }

    @PutMapping("/{id}/lines/{lineId}")
    SaleDetail replaceLine(@PathVariable long id, @PathVariable long lineId, @RequestBody LineRequest body) {
        return lines.replace(id, lineId, body.input());
    }

    @DeleteMapping("/{id}/lines/{lineId}")
    SaleDetail removeLine(@PathVariable long id, @PathVariable long lineId) {
        return lines.remove(id, lineId);
    }

    @PostMapping("/{id}/confirm")
    SaleDetail confirm(@PathVariable long id, @RequestBody(required = false) ConfirmRequest body,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        return confirmation.confirm(id, body == null ? List.of() : body.tenders(), idempotencyKey);
    }

    @PostMapping("/{id}/convert")
    ResponseEntity<SaleDetail> convert(@PathVariable long id, @RequestBody ConvertRequest body) {
        SaleDetail created = conversion.convert(id, body.targetType());
        return ResponseEntity.created(URI.create("/api/sales/" + created.id())).body(created);
    }

    @PostMapping("/{id}/cancel")
    SaleDetail cancel(@PathVariable long id, @RequestBody CancelRequest body) {
        return cancellation.cancel(id, body.reason());
    }

    @GetMapping("/{id}/withdrawal-warnings")
    List<SaleWarning> withdrawalWarnings(@PathVariable long id) {
        return lines.withdrawalWarnings(id);
    }
}
