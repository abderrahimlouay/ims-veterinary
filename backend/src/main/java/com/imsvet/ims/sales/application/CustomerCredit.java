package com.imsvet.ims.sales.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.sales.application.CustomerViews.CustomerDetail;
import com.imsvet.ims.sales.domain.Customer;
import com.imsvet.ims.sales.infrastructure.CustomerLocks;
import com.imsvet.ims.sales.infrastructure.CustomerRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The credit limit (BR-CUST-002, ADR-108, ADR-109): changed only here, with {@code customer.credit.manage}, under the
 * customer-row lock (the lock of Phase 7's credit check) and with the current version (two parallel changes: the
 * second is {@code CONCURRENT_MODIFICATION}). {@code null} = no limit, 0 = cash only, otherwise ≥ 0 with 2 decimals.
 * Every change — also to or from {@code null} — is audited {@code CREDIT_LIMIT_CHANGE} with the old and new value
 * before the customer changes, in the same transaction: a failed audit leaves the limit as it was. The audit entry
 * carries the amounts only (ADR-114).
 */
@Service
public class CustomerCredit {

    /** {@code given}: the request named {@code creditLimit} (a missing field never removes the limit). */
    public record CreditLimitInput(boolean given, String creditLimit, Long version) {
    }

    private static final int MONEY_SCALE = 2;
    private static final BigDecimal MAX = new BigDecimal("999999999999.99");

    private final CustomerLocks locks;
    private final CustomerRepository customers;
    private final CustomerDirectory directory;
    private final AuditRecorder audit;
    private final Clock clock;

    public CustomerCredit(CustomerLocks locks, CustomerRepository customers, CustomerDirectory directory,
            AuditRecorder audit, Clock clock) {
        this.locks = locks;
        this.customers = customers;
        this.directory = directory;
        this.audit = audit;
        this.clock = clock;
    }

    @PreAuthorize("hasAuthority('customer.credit.manage')")
    @Transactional
    public CustomerDetail change(long id, CreditLimitInput in) {
        BigDecimal limit = limit(in);
        Customer customer = locks.lock(id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Customer not found.", Map.of("id", id)));
        if (in.version() == null || customer.version() != in.version()) {
            throw new ObjectOptimisticLockingFailureException(Customer.class, id);
        }
        if (!Objects.equals(amount(customer.creditLimit()), amount(limit))) {
            Map<String, Object> oldValues = new HashMap<>();
            oldValues.put("creditLimit", amount(customer.creditLimit()));
            Map<String, Object> newValues = new HashMap<>();
            newValues.put("creditLimit", amount(limit));
            audit.record(AuditAction.CREDIT_LIMIT_CHANGE, "customers", id, oldValues, newValues);
            customer.changeCreditLimit(limit, clock.instant());
            customers.saveAndFlush(customer);
        }
        return directory.detail(customer);
    }

    private static BigDecimal limit(CreditLimitInput in) {
        if (!in.given()) {
            throw invalid("REQUIRED", "Give the limit, or null for no limit.");
        }
        if (in.creditLimit() == null) {
            return null;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(in.creditLimit().trim());
        } catch (NumberFormatException e) {
            throw invalid("FORMAT", "A decimal amount, or null for no limit.");
        }
        if (value.signum() < 0) {
            throw invalid("NOT_NEGATIVE", "A credit limit cannot be negative.");
        }
        if (value.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw invalid("SCALE", "At most two decimals.");
        }
        if (value.compareTo(MAX) > 0) {
            throw invalid("RANGE", "This limit is too large.");
        }
        return value.setScale(MONEY_SCALE);
    }

    private static String amount(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE).toPlainString();
    }

    private static ValidationException invalid(String code, String message) {
        return new ValidationException("The credit limit is invalid.", List.of(new Violation("creditLimit", code, message)));
    }
}
