package com.imsvet.ims.finance.api;

import com.imsvet.ims.common.idempotency.IdempotencyKeys;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.finance.application.SupplierInvoices;
import com.imsvet.ims.finance.application.SupplierInvoices.ApplicationInput;
import com.imsvet.ims.finance.application.SupplierInvoices.InvoiceInput;
import com.imsvet.ims.finance.application.SupplierInvoices.SupplierInvoiceDetail;
import com.imsvet.ims.finance.application.SupplierInvoices.SupplierInvoiceSummary;
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

/** api.md §4.6 {@code /api/supplier-invoices} (ADR-133); permissions on the service. */
@RestController
@RequestMapping("/api/supplier-invoices")
class SupplierInvoiceController {

    record ApplicationsRequest(List<ApplicationInput> allocations) {
    }

    record CancelRequest(String reason) {
    }

    private final SupplierInvoices invoices;

    SupplierInvoiceController(SupplierInvoices invoices) {
        this.invoices = invoices;
    }

    @PostMapping
    ResponseEntity<SupplierInvoiceDetail> record(@RequestBody InvoiceInput request) {
        SupplierInvoiceDetail recorded = invoices.record(request);
        return ResponseEntity.created(URI.create("/api/supplier-invoices/" + recorded.id())).body(recorded);
    }

    @GetMapping
    PageResponse<SupplierInvoiceSummary> search(@RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) Boolean creditNote,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueBefore,
            @RequestParam(defaultValue = "false") boolean open, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return invoices.search(supplierId, creditNote, dueBefore, open, page, size);
    }

    @GetMapping("/{id}")
    SupplierInvoiceDetail get(@PathVariable long id) {
        return invoices.get(id);
    }

    @PostMapping("/{id}/cancel")
    SupplierInvoiceDetail cancel(@PathVariable long id, @RequestBody CancelRequest request) {
        return invoices.cancel(id, request.reason());
    }

    @PostMapping("/{id}/applications")
    SupplierInvoiceDetail apply(@PathVariable long id, @RequestBody ApplicationsRequest request,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        return invoices.apply(id, request.allocations(), idempotencyKey);
    }
}
