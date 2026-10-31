package com.imsvet.ims.sales.api;

import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.sales.application.SalesDocuments;
import com.imsvet.ims.sales.application.SalesViews.SaleDetail;
import com.imsvet.ims.sales.application.SalesViews.SaleSummary;
import java.net.URI;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    private final SalesDocuments documents;

    SalesController(SalesDocuments documents) {
        this.documents = documents;
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
}
