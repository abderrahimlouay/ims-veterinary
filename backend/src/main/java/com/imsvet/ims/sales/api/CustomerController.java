package com.imsvet.ims.sales.api;

import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.sales.application.CustomerCredit;
import com.imsvet.ims.sales.application.CustomerDirectory;
import com.imsvet.ims.sales.application.CustomerViews.CustomerDetail;
import com.imsvet.ims.sales.application.CustomerViews.CustomerSummary;
import com.imsvet.ims.sales.application.CustomerViews.SimilarCustomer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * api.md §4.9 {@code /api/customers}; permissions on the services. No delete (405, ADR-109). The credit limit has
 * its own endpoint (ADR-108): a {@code creditLimit} sent to create or update is not part of the request and is
 * ignored (a new customer starts at 0).
 */
@RestController
@RequestMapping("/api/customers")
class CustomerController {

    /** {@code version} is required by PUT (ADR-027); type, category, terms and links are checked by the service. */
    record CustomerRequest(@NotBlank @Size(max = 150) String name, String customerType, Integer categoryId,
            @Size(max = 100) String farmingType, @Size(max = 60) String wilaya, @Size(max = 80) String commune,
            @Size(max = 2000) String address, @Size(max = 40) String phone, @Email @Size(max = 150) String email,
            @Size(max = 40) String taxId, Integer paymentTermsDays, Long veterinarianId, Long version) {

        CustomerDirectory.CustomerInput input() {
            return new CustomerDirectory.CustomerInput(name, customerType, categoryId, farmingType, wilaya, commune,
                    address, phone, email, taxId, paymentTermsDays, veterinarianId);
        }
    }

    record SimilarRequest(String name, String phone, String taxId, Long excludeId) {
    }

    private final CustomerDirectory directory;
    private final CustomerCredit credit;

    CustomerController(CustomerDirectory directory, CustomerCredit credit) {
        this.directory = directory;
        this.credit = credit;
    }

    @GetMapping
    PageResponse<CustomerSummary> search(@RequestParam(required = false) String q,
            @RequestParam(required = false) String type, @RequestParam(required = false) String wilaya,
            @RequestParam(required = false) String commune, @RequestParam(required = false) Integer categoryId,
            @RequestParam(required = false) Boolean active, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return directory.search(q, type, wilaya, commune, categoryId, active, page, size);
    }

    /** A POST so that names and phones never travel in a URL (ADR-114). */
    @PostMapping("/similar")
    List<SimilarCustomer> similar(@RequestBody SimilarRequest body) {
        return directory.similar(body.name(), body.phone(), body.taxId(), body.excludeId());
    }

    /** {@code creditLimit} must be present: {@code null} = no limit (ADR-108); a missing field is refused. */
    @PutMapping("/{id}/credit-limit")
    CustomerDetail changeCreditLimit(@PathVariable long id, @RequestBody JsonNode body) {
        JsonNode limit = body.get("creditLimit");
        JsonNode version = body.get("version");
        return credit.change(id, new CustomerCredit.CreditLimitInput(limit != null,
                limit == null || limit.isNull() ? null : limit.asString(),
                version == null || version.isNull() ? null : version.asLong()));
    }

    @PostMapping
    ResponseEntity<CustomerDetail> create(@Valid @RequestBody CustomerRequest body) {
        CustomerDetail created = directory.create(body.input());
        return ResponseEntity.created(URI.create("/api/customers/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    CustomerDetail get(@PathVariable long id) {
        return directory.get(id);
    }

    @PutMapping("/{id}")
    CustomerDetail update(@PathVariable long id, @Valid @RequestBody CustomerRequest body) {
        return directory.update(id, body.input(), body.version() == null ? -1 : body.version());
    }

    @PostMapping("/{id}/deactivate")
    CustomerDetail deactivate(@PathVariable long id) {
        return directory.setActive(id, false);
    }

    @PostMapping("/{id}/activate")
    CustomerDetail activate(@PathVariable long id) {
        return directory.setActive(id, true);
    }
}
