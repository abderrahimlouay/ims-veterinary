package com.imsvet.ims.finance.api;

import com.imsvet.ims.common.idempotency.IdempotencyKeys;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.finance.application.SupplierPayments;
import com.imsvet.ims.finance.application.SupplierPayments.AllocationInput;
import com.imsvet.ims.finance.application.SupplierPayments.Payables;
import com.imsvet.ims.finance.application.SupplierPayments.PaymentDetail;
import com.imsvet.ims.finance.application.SupplierPayments.PaymentInput;
import com.imsvet.ims.finance.application.SupplierPayments.PaymentSummary;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** api.md §4.6 (payables) and §4.12 supplier payments (ADR-132, ADR-135); permissions on the service. */
@RestController
class SupplierPaymentController {

    record AllocationsRequest(List<AllocationInput> allocations) {
    }

    record ReverseRequest(String reason) {
    }

    private final SupplierPayments payments;

    SupplierPaymentController(SupplierPayments payments) {
        this.payments = payments;
    }

    @PostMapping("/api/payments/suppliers")
    ResponseEntity<PaymentDetail> record(@RequestBody PaymentInput request,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        PaymentDetail recorded = payments.record(request, idempotencyKey);
        return ResponseEntity.created(URI.create("/api/payments/suppliers/" + recorded.id())).body(recorded);
    }

    @PostMapping("/api/payments/suppliers/{id}/allocations")
    PaymentDetail apply(@PathVariable long id, @RequestBody AllocationsRequest request,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        return payments.apply(id, request.allocations(), idempotencyKey);
    }

    @PostMapping("/api/payments/suppliers/{id}/reverse")
    PaymentDetail reverse(@PathVariable long id, @RequestBody ReverseRequest request,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        return payments.reverse(id, request.reason(), idempotencyKey);
    }

    @GetMapping("/api/payments/suppliers")
    PageResponse<PaymentSummary> search(@RequestParam(required = false) Long supplierId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return payments.search(supplierId, page, size);
    }

    @GetMapping("/api/payments/suppliers/{id}")
    PaymentDetail get(@PathVariable long id) {
        return payments.get(id);
    }

    @GetMapping("/api/suppliers/{id}/payables")
    Payables payables(@PathVariable long id) {
        return payments.payables(id);
    }
}
