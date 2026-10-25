package com.imsvet.ims.sales.application;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.sales.application.CustomerViews.CategoryRef;
import com.imsvet.ims.sales.application.CustomerViews.CustomerDetail;
import com.imsvet.ims.sales.application.CustomerViews.CustomerRef;
import com.imsvet.ims.sales.application.CustomerViews.CustomerSummary;
import com.imsvet.ims.sales.application.CustomerViews.SimilarCustomer;
import com.imsvet.ims.sales.domain.Customer;
import com.imsvet.ims.sales.domain.CustomerCategory;
import com.imsvet.ims.sales.domain.CustomerKeys;
import com.imsvet.ims.sales.domain.CustomerType;
import com.imsvet.ims.sales.infrastructure.CustomerCategoryRepository;
import com.imsvet.ims.sales.infrastructure.CustomerRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer directory (FR-SALE-004, ADR-109, ADR-110): search, create, edit with the version, deactivate and
 * reactivate — never delete (BR-CUST-003). The follow-up veterinarian is an active customer of type veterinarian,
 * not the customer itself; a referenced veterinarian's type cannot change (BR-CUST-001). The same name and phone, or
 * the same tax id, is refused with the other customer's id and the field only (BR-CUST-005); the database keys
 * refuse it too under concurrency. The credit limit is not edited here (ADR-108). Personal data is never logged.
 */
@Service
public class CustomerDirectory {

    /** Lengths and the e-mail format are checked by the API layer. */
    public record CustomerInput(String name, String customerType, Integer categoryId, String farmingType,
            String wilaya, String commune, String address, String phone, String email, String taxId,
            Integer paymentTermsDays, Long veterinarianId) {
    }

    private static final int MAX_TERMS = Short.MAX_VALUE;

    private final CustomerRepository customers;
    private final CustomerCategoryRepository categories;
    private final Clock clock;

    public CustomerDirectory(CustomerRepository customers, CustomerCategoryRepository categories, Clock clock) {
        this.customers = customers;
        this.categories = categories;
        this.clock = clock;
    }

    /** {@code q}: part of the name or phone, or the whole phone or tax id in any format; by name. */
    @PreAuthorize("hasAuthority('customer.read')")
    @Transactional(readOnly = true)
    public PageResponse<CustomerSummary> search(String q, String type, String wilaya, String commune,
            Integer categoryId, Boolean active, int page, int size) {
        String term = blankToNull(q);
        CustomerType wanted = type == null || type.isBlank() ? null : CustomerType.fromCode(type)
                .orElseThrow(() -> invalid("type", "UNKNOWN", "Breeder, veterinarian or other."));
        Page<Customer> found = customers.search(term == null ? null : "%" + term.replaceAll("([!%_])", "!$1") + "%",
                CustomerKeys.phoneKey(term), CustomerKeys.taxKey(term), wanted, blankToNull(wilaya),
                blankToNull(commune), categoryId, active, PageRequests.of(page, size, Sort.unsorted()));
        Map<Integer, CustomerCategory> refs = categoriesOf(found.getContent());
        return PageResponse.of(found, c -> new CustomerSummary(c.id(), c.name(), c.type().code(),
                categoryRef(refs.get(c.categoryId())), c.wilaya(), c.commune(), c.phone(), c.active()));
    }

    @PreAuthorize("hasAuthority('customer.read')")
    @Transactional(readOnly = true)
    public CustomerDetail get(long id) {
        return detail(find(id));
    }

    /** Possible duplicates before saving (ADR-110): ids and the matching field only. */
    @PreAuthorize("hasAuthority('customer.read')")
    @Transactional(readOnly = true)
    public List<SimilarCustomer> similar(String name, String phone, String taxId, Long excludeId) {
        Map<Long, String> found = new LinkedHashMap<>();
        String taxKey = CustomerKeys.taxKey(taxId);
        if (taxKey != null) {
            customers.findByTaxId(taxKey).ifPresent(c -> found.putIfAbsent(c.id(), "taxId"));
        }
        String phoneKey = CustomerKeys.phoneKey(phone);
        if (phoneKey != null) {
            customers.findByPhoneKey(phoneKey).forEach(c -> found.putIfAbsent(c.id(), "phone"));
        }
        String trimmed = blankToNull(name);
        if (trimmed != null) {
            customers.findByName(trimmed).forEach(c -> found.putIfAbsent(c.id(), "name"));
        }
        found.remove(excludeId);
        return found.entrySet().stream().map(e -> new SimilarCustomer(e.getKey(), e.getValue())).toList();
    }

    @PreAuthorize("hasAuthority('customer.manage')")
    @Transactional
    public CustomerDetail create(CustomerInput in) {
        Customer.Details details = validate(in, null);
        rejectDuplicate(details, null);
        return detail(save(new Customer(details, clock.instant())));
    }

    @PreAuthorize("hasAuthority('customer.manage')")
    @Transactional
    public CustomerDetail update(long id, CustomerInput in, long version) {
        Customer customer = find(id);
        if (customer.version() != version) {
            throw new ObjectOptimisticLockingFailureException(Customer.class, id);
        }
        Customer.Details details = validate(in, id);
        if (customer.type() == CustomerType.VETERINARIAN && details.type() != CustomerType.VETERINARIAN
                && customers.existsByVeterinarianId(id)) {
            throw new BusinessException(ErrorCode.RESOURCE_IN_USE,
                    "This veterinarian follows other customers; their type cannot change.",
                    Map.of("field", "customerType", "customerId", id));
        }
        rejectDuplicate(details, id);
        customer.update(details, clock.instant());
        return detail(save(customer));
    }

    /** A deactivated customer gets no new sale or payment (BR-CUST-004, from Phase 7); links stay. */
    @PreAuthorize("hasAuthority('customer.manage')")
    @Transactional
    public CustomerDetail setActive(long id, boolean active) {
        Customer customer = find(id);
        customer.setActive(active, clock.instant());
        return detail(customers.saveAndFlush(customer));
    }

    private Customer.Details validate(CustomerInput in, Long self) {
        List<Violation> violations = new ArrayList<>();
        String name = blankToNull(in.name());
        if (name == null) {
            violations.add(new Violation("name", "REQUIRED", "The name is required."));
        }
        CustomerType type = in.customerType() == null || in.customerType().isBlank() ? CustomerType.BREEDER
                : CustomerType.fromCode(in.customerType()).orElse(null);
        if (type == null) {
            violations.add(new Violation("customerType", "UNKNOWN", "Breeder, veterinarian or other."));
        }
        if (in.categoryId() != null && categories.findById(in.categoryId()).isEmpty()) {
            violations.add(new Violation("categoryId", "UNKNOWN", "Unknown customer category."));
        }
        int terms = in.paymentTermsDays() == null ? 0 : in.paymentTermsDays();
        if (terms < 0 || terms > MAX_TERMS) {
            violations.add(new Violation("paymentTermsDays", "RANGE", "Payment terms are a number of days, 0 or more."));
        }
        String phoneKey = CustomerKeys.phoneKey(in.phone());
        if (phoneKey != null && phoneKey.length() > 15) {
            violations.add(new Violation("phone", "FORMAT", "At most 15 digits."));
        }
        if (in.veterinarianId() != null) {
            if (in.veterinarianId().equals(self)) {
                violations.add(new Violation("veterinarianId", "SELF", "A customer cannot follow itself."));
            } else {
                Customer vet = customers.findById(in.veterinarianId()).orElse(null);
                if (vet == null || vet.type() != CustomerType.VETERINARIAN || !vet.active()) {
                    violations.add(new Violation("veterinarianId", "NOT_VETERINARIAN",
                            "The follow-up veterinarian is an active customer of type veterinarian."));
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException("The customer is invalid.", violations);
        }
        return new Customer.Details(name, type, in.categoryId(), blankToNull(in.farmingType()), blankToNull(in.wilaya()),
                blankToNull(in.commune()), blankToNull(in.address()), blankToNull(in.phone()), blankToNull(in.email()),
                blankToNull(in.taxId()), terms, in.veterinarianId());
    }

    /** ADR-110: the other customer's id and the field only; never its data. */
    private void rejectDuplicate(Customer.Details d, Long self) {
        String taxKey = CustomerKeys.taxKey(d.taxId());
        if (taxKey != null) {
            customers.findByTaxId(taxKey).filter(c -> !c.id().equals(self))
                    .ifPresent(c -> { throw duplicate("taxId", c.id()); });
        }
        String phoneKey = CustomerKeys.phoneKey(d.phone());
        if (phoneKey != null) {
            customers.findByNameAndPhoneKey(d.name(), phoneKey).stream().filter(c -> !c.id().equals(self)).findFirst()
                    .ifPresent(c -> { throw duplicate("phone", c.id()); });
        }
    }

    /** A parallel creation passed the checks: the unique key that refuses it names the field. */
    private Customer save(Customer customer) {
        try {
            return customers.saveAndFlush(customer);
        } catch (DataIntegrityViolationException e) {
            String cause = String.valueOf(e.getMostSpecificCause().getMessage());
            if (cause.contains("uq_customers_tax_id")) {
                throw duplicate("taxId", null);
            }
            if (cause.contains("uq_customers_name_phone")) {
                throw duplicate("phone", null);
            }
            throw e;
        }
    }

    private CustomerDetail detail(Customer c) {
        CategoryRef category = c.categoryId() == null ? null
                : categoryRef(categories.findById(c.categoryId()).orElseThrow());
        CustomerRef vet = c.veterinarianId() == null ? null : customers.findById(c.veterinarianId())
                .map(v -> new CustomerRef(v.id(), v.name())).orElse(null);
        return new CustomerDetail(c.id(), c.name(), c.type().code(), category, c.farmingType(), c.wilaya(),
                c.commune(), c.address(), c.phone(), c.email(), c.taxId(), c.paymentTermsDays(), vet, c.creditLimit(),
                c.active(), c.createdAt(), c.updatedAt(), c.version());
    }

    private Map<Integer, CustomerCategory> categoriesOf(List<Customer> found) {
        return found.stream().map(Customer::categoryId).filter(Objects::nonNull).distinct()
                .map(categories::findById).flatMap(java.util.Optional::stream)
                .collect(Collectors.toMap(CustomerCategory::id, c -> c));
    }

    private static CategoryRef categoryRef(CustomerCategory c) {
        return c == null ? null : new CategoryRef(c.id(), c.name(), c.discountPct());
    }

    Customer find(long id) {
        return customers.findById(id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Customer not found.", Map.of("id", id)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BusinessException duplicate(String field, Long customerId) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("field", field);
        if (customerId != null) {
            context.put("customerId", customerId);
        }
        return new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "Another customer has the same "
                + (field.equals("taxId") ? "tax id." : "name and phone."), context);
    }

    private static ValidationException invalid(String field, String code, String message) {
        return new ValidationException("The search is invalid.", List.of(new Violation(field, code, message)));
    }
}
