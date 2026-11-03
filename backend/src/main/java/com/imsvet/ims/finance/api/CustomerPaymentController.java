package com.imsvet.ims.finance.api;

import com.imsvet.ims.common.idempotency.IdempotencyKeys;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.finance.application.AccountPaymentViews.OpenItems;
import com.imsvet.ims.finance.application.AccountPaymentViews.PaymentDetail;
import com.imsvet.ims.finance.application.AccountPaymentViews.PaymentSummary;
import com.imsvet.ims.finance.application.AccountPayments;
import com.imsvet.ims.finance.application.AccountPayments.AllocationInput;
import com.imsvet.ims.finance.application.AccountPayments.PaymentInput;
import com.imsvet.ims.finance.application.AccountStatements;
import com.imsvet.ims.finance.application.AccountStatements.Statement;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** api.md §4.9 (statement, open invoices), §4.12 and §5.9 customer payments on account; permissions on the service. */
@RestController
class CustomerPaymentController {

    record AllocationsRequest(List<AllocationInput> allocations) {
    }

    private final AccountPayments payments;
    private final AccountStatements statements;

    CustomerPaymentController(AccountPayments payments, AccountStatements statements) {
        this.payments = payments;
        this.statements = statements;
    }

    @PostMapping("/api/payments/customers")
    ResponseEntity<PaymentDetail> record(@RequestBody PaymentInput request,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        PaymentDetail recorded = payments.record(request, idempotencyKey);
        return ResponseEntity.created(URI.create("/api/payments/customers/" + recorded.id())).body(recorded);
    }

    @PostMapping("/api/payments/customers/{id}/allocations")
    PaymentDetail apply(@PathVariable long id, @RequestBody AllocationsRequest request,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        return payments.apply(id, request.allocations(), idempotencyKey);
    }

    @GetMapping("/api/payments/customers")
    PageResponse<PaymentSummary> search(@RequestParam(required = false) Long customerId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return payments.search(customerId, page, size);
    }

    @GetMapping("/api/payments/customers/{id}")
    PaymentDetail get(@PathVariable long id) {
        return payments.get(id);
    }

    @GetMapping("/api/customers/{id}/statement")
    Statement statement(@PathVariable long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return statements.statement(id, from, to);
    }

    @GetMapping("/api/customers/{id}/open-invoices")
    OpenItems openItems(@PathVariable long id) {
        return payments.openItems(id);
    }
}
