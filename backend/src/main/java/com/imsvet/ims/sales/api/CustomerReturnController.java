package com.imsvet.ims.sales.api;

import com.imsvet.ims.common.idempotency.IdempotencyKeys;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.sales.application.CustomerReturns;
import com.imsvet.ims.sales.application.CustomerReturns.PreviewInput;
import com.imsvet.ims.sales.application.CustomerReturns.ReturnInput;
import com.imsvet.ims.sales.application.CustomerReturns.ReturnLineInput;
import com.imsvet.ims.sales.application.PaymentRecorder;
import com.imsvet.ims.sales.application.ReturnViews.CustomerReturnDetail;
import com.imsvet.ims.sales.application.ReturnViews.CustomerReturnSummary;
import com.imsvet.ims.sales.application.ReturnViews.ReturnPreview;
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

/** api.md §4.11 and §5.8 {@code /api/returns/customer}; permissions on the service. */
@RestController
@RequestMapping("/api/returns/customer")
class CustomerReturnController {

    /** Amounts are never part of the request: the server computes the credit note (BR-SALE-008, ADR-130). */
    record LineRequest(Long saleLineId, BigDecimal quantity, String unit, Boolean sealed, String disposition,
            String discardReason, String discardNote) {

        ReturnLineInput input() {
            return new ReturnLineInput(saleLineId, quantity, unit, sealed, disposition, discardReason, discardNote);
        }
    }

    record RefundRequest(String method, BigDecimal amount, String reference) {
    }

    record ReturnRequest(Long documentId, String reason, List<LineRequest> lines, List<RefundRequest> refund) {

        ReturnInput input() {
            return new ReturnInput(documentId, reason, lineInputs(lines), refund == null ? List.of() : refund.stream()
                    .map(r -> new PaymentRecorder.Tender(r.method(), r.amount(), r.reference())).toList());
        }
    }

    record PreviewRequest(Long documentId, List<LineRequest> lines) {
    }

    private final CustomerReturns returns;

    CustomerReturnController(CustomerReturns returns) {
        this.returns = returns;
    }

    @PostMapping("/preview")
    ReturnPreview preview(@RequestBody PreviewRequest request) {
        return returns.preview(new PreviewInput(request.documentId(), lineInputs(request.lines())));
    }

    @PostMapping
    ResponseEntity<CustomerReturnDetail> create(@RequestBody ReturnRequest request,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        CustomerReturnDetail created = returns.create(request.input(), idempotencyKey);
        return ResponseEntity.created(URI.create("/api/returns/customer/" + created.id())).body(created);
    }

    @GetMapping
    PageResponse<CustomerReturnSummary> search(@RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long documentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return returns.search(customerId, documentId, from, to, page, size);
    }

    @GetMapping("/{id}")
    CustomerReturnDetail get(@PathVariable long id) {
        return returns.get(id);
    }

    private static List<ReturnLineInput> lineInputs(List<LineRequest> lines) {
        return lines == null ? null : lines.stream().map(LineRequest::input).toList();
    }
}
