package com.imsvet.ims.purchasing.application;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.purchasing.application.SupplierViews.SupplierDetail;
import com.imsvet.ims.purchasing.domain.Supplier;
import com.imsvet.ims.purchasing.domain.SupplierKind;
import com.imsvet.ims.purchasing.infrastructure.SupplierRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The supplier directory (FR-PURCH-001, ADR-100): name unique ignoring case and accents, kind laboratory or
 * wholesaler (wholesaler by default), optional tax id, payment terms in days ≥ 0; deactivated and reactivated at
 * any time, never deleted (BR-PURCH-011).
 */
@Service
public class SupplierDirectory {

    /** Lengths and the e-mail format are checked by the API layer. */
    public record SupplierInput(String name, String kind, String taxId, String phone, String email, String address,
            Integer paymentTermsDays) {
    }

    private static final int MAX_TERMS = Short.MAX_VALUE;

    private final SupplierRepository suppliers;
    private final Clock clock;

    public SupplierDirectory(SupplierRepository suppliers, Clock clock) {
        this.suppliers = suppliers;
        this.clock = clock;
    }

    /** {@code q} contained in the name, ignoring case and accents; {@code active} optional. */
    @PreAuthorize("hasAuthority('supplier.read')")
    @Transactional(readOnly = true)
    public PageResponse<SupplierDetail> search(String q, Boolean active, int page, int size) {
        String term = q == null || q.isBlank() ? null : "%" + q.trim().replaceAll("([!%_])", "!$1") + "%";
        return PageResponse.of(suppliers.search(term, active, PageRequests.of(page, size, Sort.unsorted())),
                SupplierViews::detail);
    }

    @PreAuthorize("hasAuthority('supplier.read')")
    @Transactional(readOnly = true)
    public SupplierDetail get(long id) {
        return SupplierViews.detail(find(id));
    }

    @PreAuthorize("hasAuthority('supplier.manage')")
    @Transactional
    public SupplierDetail create(SupplierInput in) {
        Supplier.Details details = validate(in);
        rejectDuplicate(details.name(), null);
        return SupplierViews.detail(save(new Supplier(details, clock.instant())));
    }

    @PreAuthorize("hasAuthority('supplier.manage')")
    @Transactional
    public SupplierDetail update(long id, SupplierInput in, long version) {
        Supplier supplier = find(id);
        if (supplier.version() != version) {
            throw new ObjectOptimisticLockingFailureException(Supplier.class, id);
        }
        Supplier.Details details = validate(in);
        rejectDuplicate(details.name(), supplier.id());
        supplier.update(details, clock.instant());
        return SupplierViews.detail(save(supplier));
    }

    /** Open orders stay; their receipts are refused while the supplier is inactive (ADR-100). */
    @PreAuthorize("hasAuthority('supplier.manage')")
    @Transactional
    public SupplierDetail setActive(long id, boolean active) {
        Supplier supplier = find(id);
        supplier.setActive(active, clock.instant());
        return SupplierViews.detail(save(supplier));
    }

    private Supplier.Details validate(SupplierInput in) {
        SupplierKind kind = in.kind() == null || in.kind().isBlank() ? SupplierKind.WHOLESALER
                : SupplierKind.fromCode(in.kind()).orElseThrow(() -> invalid("kind", "UNKNOWN",
                        "Laboratory or wholesaler."));
        int terms = in.paymentTermsDays() == null ? 0 : in.paymentTermsDays();
        if (terms < 0 || terms > MAX_TERMS) {
            throw invalid("paymentTermsDays", "RANGE", "Payment terms are a number of days, 0 or more.");
        }
        return new Supplier.Details(in.name().trim(), kind, blankToNull(in.taxId()), blankToNull(in.phone()),
                blankToNull(in.email()), blankToNull(in.address()), terms);
    }

    private void rejectDuplicate(String name, Long self) {
        if (suppliers.findByName(name).filter(s -> !s.id().equals(self)).isPresent()) {
            throw duplicate();
        }
    }

    private Supplier save(Supplier supplier) {
        try {
            return suppliers.saveAndFlush(supplier);
        } catch (DataIntegrityViolationException e) {
            throw duplicate();
        }
    }

    Supplier find(long id) {
        return suppliers.findById(id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Supplier not found.", Map.of("id", id)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BusinessException duplicate() {
        return new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "A supplier with this name already exists.",
                Map.of("field", "name"));
    }

    private static ValidationException invalid(String field, String code, String message) {
        return new ValidationException("The supplier is invalid.", List.of(new Violation(field, code, message)));
    }
}
