package com.imsvet.ims.purchasing.api;

import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.purchasing.application.SupplierDirectory;
import com.imsvet.ims.purchasing.application.SupplierDirectory.SupplierInput;
import com.imsvet.ims.purchasing.application.SupplierPrices;
import com.imsvet.ims.purchasing.application.SupplierViews.PriceEntry;
import com.imsvet.ims.purchasing.application.SupplierViews.SupplierDetail;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** api.md §4.6 {@code /api/suppliers}; permissions on the services. */
@RestController
@RequestMapping("/api/suppliers")
class SupplierController {

    /** {@code version} is required by PUT (ADR-027); kind and terms are checked by the service. */
    record SupplierRequest(@NotBlank @Size(max = 150) String name, String kind, @Size(max = 40) String taxId,
            @Size(max = 40) String phone, @Email @Size(max = 150) String email, @Size(max = 2000) String address,
            Integer paymentTermsDays, Long version) {

        SupplierInput input() {
            return new SupplierInput(name, kind, taxId, phone, email, address, paymentTermsDays);
        }
    }

    record PriceRequest(Long productId, BigDecimal unitPrice, BigDecimal discountPct, LocalDate validFrom) {
    }

    private final SupplierDirectory directory;
    private final SupplierPrices prices;

    SupplierController(SupplierDirectory directory, SupplierPrices prices) {
        this.directory = directory;
        this.prices = prices;
    }

    @GetMapping
    PageResponse<SupplierDetail> search(@RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean active, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return directory.search(q, active, page, size);
    }

    @PostMapping
    ResponseEntity<SupplierDetail> create(@Valid @RequestBody SupplierRequest body) {
        SupplierDetail created = directory.create(body.input());
        return ResponseEntity.created(URI.create("/api/suppliers/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    SupplierDetail get(@PathVariable long id) {
        return directory.get(id);
    }

    @PutMapping("/{id}")
    SupplierDetail update(@PathVariable long id, @Valid @RequestBody SupplierRequest body) {
        return directory.update(id, body.input(), body.version() == null ? -1 : body.version());
    }

    @PostMapping("/{id}/deactivate")
    SupplierDetail deactivate(@PathVariable long id) {
        return directory.setActive(id, false);
    }

    @PostMapping("/{id}/activate")
    SupplierDetail activate(@PathVariable long id) {
        return directory.setActive(id, true);
    }

    @GetMapping("/{id}/prices")
    PageResponse<PriceEntry> prices(@PathVariable long id, @RequestParam(required = false) Long productId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return prices.history(id, productId, page, size);
    }

    @PostMapping("/{id}/prices")
    ResponseEntity<PriceEntry> addPrice(@PathVariable long id, @RequestBody PriceRequest body) {
        PriceEntry created = prices.add(id, new SupplierPrices.PriceInput(body.productId(), body.unitPrice(),
                body.discountPct(), body.validFrom()));
        return ResponseEntity.created(URI.create("/api/suppliers/" + id + "/prices")).body(created);
    }
}
