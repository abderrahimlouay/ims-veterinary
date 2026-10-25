package com.imsvet.ims.sales.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.sales.application.CustomerViews.CustomerPriceView;
import com.imsvet.ims.sales.application.CustomerViews.PriceProduct;
import com.imsvet.ims.sales.domain.CustomerPrice;
import com.imsvet.ims.sales.infrastructure.CustomerLocks;
import com.imsvet.ims.sales.infrastructure.CustomerPriceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A customer's own prices (FR-SALE-010, BR-CUST-006, ADR-111): per base unit, > 0 with at most 4 decimals (like the
 * product price), one per product, set, changed or removed one at a time with {@code customer.price.manage}. Each
 * change is audited {@code PRICE_CHANGE} before it happens, in the same transaction (old and new value, the product
 * id and amounts only): a failed audit leaves the price as it was. The customer row is locked first, so edits of one
 * customer's prices are serialised: a parallel creation of the same price finds it and is refused cleanly
 * ({@code CONCURRENT_MODIFICATION}); changes and removals carry the version. A price for an archived product cannot
 * be set (existing ones are kept and can be removed). An inactive customer's prices can still be edited.
 */
@Service
public class CustomerPrices {

    private static final int PRICE_SCALE = 4;
    private static final BigDecimal MAX = new BigDecimal("9999999999.9999");

    private final CustomerPriceRepository prices;
    private final CustomerLocks locks;
    private final CustomerDirectory directory;
    private final ProductLookup products;
    private final AuditRecorder audit;
    private final Clock clock;

    public CustomerPrices(CustomerPriceRepository prices, CustomerLocks locks, CustomerDirectory directory,
            ProductLookup products, AuditRecorder audit, Clock clock) {
        this.prices = prices;
        this.locks = locks;
        this.directory = directory;
        this.products = products;
        this.audit = audit;
        this.clock = clock;
    }

    /** By product name. */
    @PreAuthorize("hasAuthority('customer.read')")
    @Transactional(readOnly = true)
    public List<CustomerPriceView> list(long customerId) {
        directory.find(customerId);
        List<CustomerPrice> found = prices.findByIdCustomerId(customerId);
        Map<Long, ProductRef> refs = products.findAll(found.stream().map(CustomerPrice::productId)
                .collect(Collectors.toSet()));
        return found.stream().map(p -> view(p, refs.get(p.productId())))
                .sorted(Comparator.comparing(v -> v.product().commercialName())).toList();
    }

    /** {@code version} null to create; the current version to change. */
    @PreAuthorize("hasAuthority('customer.price.manage')")
    @Transactional
    public CustomerPriceView set(long customerId, long productId, BigDecimal unitPrice, Long version) {
        BigDecimal price = price(unitPrice);
        ProductRef product = products.find(productId).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Product not found.", Map.of("id", productId)));
        if (product.archived()) {
            throw new BusinessException(ErrorCode.PRODUCT_ARCHIVED, "This product is archived.",
                    Map.of("productId", productId));
        }
        lockCustomer(customerId);
        CustomerPrice.Key key = new CustomerPrice.Key(customerId, productId);
        CustomerPrice existing = prices.findById(key).orElse(null);
        if (existing == null ? version != null : version == null || existing.version() != version) {
            throw new ObjectOptimisticLockingFailureException(CustomerPrice.class, key);
        }
        if (existing != null && existing.unitPrice().compareTo(price) == 0) {
            return view(existing, product);
        }
        audit(customerId, productId, existing == null ? null : existing.unitPrice(), price);
        CustomerPrice saved;
        if (existing == null) {
            saved = prices.saveAndFlush(new CustomerPrice(customerId, productId, price, clock.instant()));
        } else {
            existing.change(price, clock.instant());
            saved = prices.saveAndFlush(existing);
        }
        return view(saved, product);
    }

    @PreAuthorize("hasAuthority('customer.price.manage')")
    @Transactional
    public void remove(long customerId, long productId, Long version) {
        lockCustomer(customerId);
        CustomerPrice.Key key = new CustomerPrice.Key(customerId, productId);
        CustomerPrice existing = prices.findById(key).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "This customer has no own price for this product.", Map.of("productId", productId)));
        if (version == null || existing.version() != version) {
            throw new ObjectOptimisticLockingFailureException(CustomerPrice.class, key);
        }
        audit(customerId, productId, existing.unitPrice(), null);
        prices.delete(existing);
        prices.flush();
    }

    private void lockCustomer(long customerId) {
        locks.lock(customerId).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Customer not found.", Map.of("id", customerId)));
    }

    /** Amounts and ids only (ADR-114). */
    private void audit(long customerId, long productId, BigDecimal before, BigDecimal after) {
        Map<String, Object> oldValues = new HashMap<>();
        oldValues.put("productId", productId);
        oldValues.put("unitPrice", before == null ? null : before.toPlainString());
        Map<String, Object> newValues = new HashMap<>();
        newValues.put("productId", productId);
        newValues.put("unitPrice", after == null ? null : after.toPlainString());
        audit.record(AuditAction.PRICE_CHANGE, "customer_product_prices", customerId, oldValues, newValues);
    }

    private static BigDecimal price(BigDecimal value) {
        if (value == null) {
            throw invalid("REQUIRED", "The price is required.");
        }
        if (value.signum() <= 0) {
            throw invalid("POSITIVE", "A customer price is greater than zero.");
        }
        if (value.stripTrailingZeros().scale() > PRICE_SCALE) {
            throw invalid("SCALE", "At most four decimals.");
        }
        if (value.compareTo(MAX) > 0) {
            throw invalid("RANGE", "This price is too large.");
        }
        return value.setScale(PRICE_SCALE);
    }

    private static CustomerPriceView view(CustomerPrice p, ProductRef product) {
        return new CustomerPriceView(new PriceProduct(product.id(), product.sku(), product.commercialName(),
                product.baseUnit(), product.archived()), p.unitPrice(), p.updatedAt(), p.version());
    }

    private static ValidationException invalid(String code, String message) {
        return new ValidationException("The customer price is invalid.", List.of(new Violation("unitPrice", code, message)));
    }
}
