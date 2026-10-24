package com.imsvet.ims.purchasing.api;

import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.purchasing.application.ReturnViews.SupplierReturnDetail;
import com.imsvet.ims.purchasing.application.ReturnViews.SupplierReturnSummary;
import com.imsvet.ims.purchasing.application.SupplierReturns;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** api.md §4.11 {@code /api/returns/supplier}; permissions on the service. */
@RestController
@RequestMapping("/api/returns/supplier")
class SupplierReturnController {

    record LineRequest(Long lotId, BigDecimal quantity, Long unitId) {
    }

    record ReturnRequest(Long supplierId, String reason, List<LineRequest> lines) {

        SupplierReturns.ReturnInput input() {
            return new SupplierReturns.ReturnInput(supplierId, reason, lines == null ? null : lines.stream()
                    .map(l -> new SupplierReturns.ReturnedLine(l.lotId(), l.quantity(), l.unitId())).toList());
        }
    }

    private final SupplierReturns returns;

    SupplierReturnController(SupplierReturns returns) {
        this.returns = returns;
    }

    @GetMapping
    PageResponse<SupplierReturnSummary> search(@RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return returns.search(supplierId, from, to, page, size);
    }

    @PostMapping
    ResponseEntity<SupplierReturnDetail> create(@RequestBody ReturnRequest body) {
        SupplierReturnDetail created = returns.create(body.input());
        return ResponseEntity.created(URI.create("/api/returns/supplier/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    SupplierReturnDetail get(@PathVariable long id) {
        return returns.get(id);
    }
}
