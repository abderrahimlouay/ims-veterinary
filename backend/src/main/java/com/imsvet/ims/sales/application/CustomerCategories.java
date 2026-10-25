package com.imsvet.ims.sales.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.sales.application.CustomerViews.CategoryView;
import com.imsvet.ims.sales.domain.CustomerCategory;
import com.imsvet.ims.sales.infrastructure.CustomerCategoryRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer categories (FR-SALE-005, BR-CUST-007, ADR-112): name unique ignoring case and accents, discount 0–100 %
 * with at most 2 decimals; renamed, never deleted. A change of the discount is audited {@code PRICE_CHANGE} with
 * the old and new value in the same transaction (the initial discount is not a change, BR-PROD-006); it never
 * rewrites existing documents, whose lines store what they applied (Phase 7).
 */
@Service
public class CustomerCategories {

    public record CategoryInput(String name, BigDecimal discountPct) {
    }

    private static final int DISCOUNT_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final CustomerCategoryRepository categories;
    private final AuditRecorder audit;
    private final Clock clock;

    public CustomerCategories(CustomerCategoryRepository categories, AuditRecorder audit, Clock clock) {
        this.categories = categories;
        this.audit = audit;
        this.clock = clock;
    }

    @PreAuthorize("hasAuthority('customer.read')")
    @Transactional(readOnly = true)
    public List<CategoryView> list() {
        return categories.findAllByOrderByNameAsc().stream().map(CustomerViews::category).toList();
    }

    @PreAuthorize("hasAuthority('customer.price.manage')")
    @Transactional
    public CategoryView create(CategoryInput in) {
        String name = name(in.name());
        rejectDuplicate(name, null);
        return CustomerViews.category(save(new CustomerCategory(name, discount(in.discountPct()), clock.instant())));
    }

    @PreAuthorize("hasAuthority('customer.price.manage')")
    @Transactional
    public CategoryView update(int id, CategoryInput in, long version) {
        CustomerCategory category = categories.findById(id).orElseThrow(() -> new BusinessException(
                ErrorCode.NOT_FOUND, "Customer category not found.", Map.of("id", id)));
        if (category.version() != version) {
            throw new ObjectOptimisticLockingFailureException(CustomerCategory.class, id);
        }
        String name = name(in.name());
        BigDecimal discount = discount(in.discountPct());
        rejectDuplicate(name, id);
        if (category.discountPct().compareTo(discount) != 0) {
            audit.record(AuditAction.PRICE_CHANGE, "customer_categories", (long) id,
                    Map.of("discountPct", category.discountPct().toPlainString()),
                    Map.of("discountPct", discount.toPlainString()));
        }
        category.update(name, discount, clock.instant());
        return CustomerViews.category(save(category));
    }

    private static String name(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty()) {
            throw invalid("name", "REQUIRED", "The name is required.");
        }
        if (name.length() > 80) {
            throw invalid("name", "SIZE", "At most 80 characters.");
        }
        return name;
    }

    private static BigDecimal discount(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(DISCOUNT_SCALE);
        }
        if (value.signum() < 0 || value.compareTo(HUNDRED) > 0) {
            throw invalid("discountPct", "RANGE", "A discount is between 0 and 100.");
        }
        if (value.stripTrailingZeros().scale() > DISCOUNT_SCALE) {
            throw invalid("discountPct", "SCALE", "At most two decimals.");
        }
        return value.setScale(DISCOUNT_SCALE);
    }

    private void rejectDuplicate(String name, Integer self) {
        if (categories.findByName(name).filter(c -> !c.id().equals(self)).isPresent()) {
            throw duplicate();
        }
    }

    private CustomerCategory save(CustomerCategory category) {
        try {
            return categories.saveAndFlush(category);
        } catch (DataIntegrityViolationException e) {
            throw duplicate();
        }
    }

    private static BusinessException duplicate() {
        return new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "A customer category with this name already exists.",
                Map.of("field", "name"));
    }

    private static ValidationException invalid(String field, String code, String message) {
        return new ValidationException("The customer category is invalid.", List.of(new Violation(field, code, message)));
    }
}
