package com.imsvet.ims.sales.application;

import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.catalog.application.ProductLookup.UnitRef;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.common.security.Permissions;
import com.imsvet.ims.sales.domain.Customer;
import com.imsvet.ims.sales.domain.CustomerCategory;
import com.imsvet.ims.sales.domain.CustomerPrice;
import com.imsvet.ims.sales.domain.PriceResolver;
import com.imsvet.ims.sales.domain.PriceResolver.Facts;
import com.imsvet.ims.sales.domain.PriceResolver.PriceRefusal;
import com.imsvet.ims.sales.domain.PriceResolver.Request;
import com.imsvet.ims.sales.domain.PriceResolver.Resolution;
import com.imsvet.ims.sales.infrastructure.CustomerCategoryRepository;
import com.imsvet.ims.sales.infrastructure.CustomerPriceRepository;
import com.imsvet.ims.sales.infrastructure.CustomerRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Port for the sales lines of Phase 7 (BR-SALE-006/007, ADR-107, ADR-113): gathers the facts of a customer, product
 * and unit and lets {@link PriceResolver} decide. No endpoint: the caller checks its own permission; the rights for
 * a manual price ({@code price.edit}) and a discount above the entitlement ({@code sale.discount}) are read here from
 * the signed-in user, never from the caller. Whether the customer may buy (active, credit) is the sale's rule, not
 * this one's. Joins the caller's transaction.
 */
@Service
@InternalService
public class CustomerPricing {

    private static final String PRICE_EDIT = "price.edit";
    private static final String SALE_DISCOUNT = "sale.discount";

    private final CustomerRepository customers;
    private final CustomerCategoryRepository categories;
    private final CustomerPriceRepository prices;
    private final ProductLookup products;

    public CustomerPricing(CustomerRepository customers, CustomerCategoryRepository categories,
            CustomerPriceRepository prices, ProductLookup products) {
        this.customers = customers;
        this.categories = categories;
        this.prices = prices;
        this.products = products;
    }

    /**
     * {@code unitId} {@code null} for the base unit; {@code manualUnitPrice} (per unit) and
     * {@code manualDiscountPct} {@code null} when not given. Unrounded (ADR-093).
     */
    @Transactional(readOnly = true)
    public Resolution resolve(long customerId, long productId, Long unitId, BigDecimal manualUnitPrice,
            BigDecimal manualDiscountPct) {
        Customer customer = customers.findById(customerId).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Customer not found.", Map.of("id", customerId)));
        ProductRef product = products.find(productId).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Product not found.", Map.of("id", productId)));
        BigDecimal factor = BigDecimal.ONE;
        BigDecimal unitPrice = null;
        if (unitId != null) {
            UnitRef unit = products.unit(productId, unitId).orElseThrow(
                    () -> new BusinessException(ErrorCode.NOT_FOUND, "Unit not found.", Map.of("id", unitId)));
            if (!unit.sellable()) {
                throw new BusinessException(ErrorCode.UNIT_NOT_SELLABLE, "This unit cannot be sold.",
                        Map.of("unitId", unitId));
            }
            factor = unit.factorToBase();
            unitPrice = unit.salePrice();
        }
        BigDecimal own = prices.findById(new CustomerPrice.Key(customerId, productId))
                .map(CustomerPrice::unitPrice).orElse(null);
        BigDecimal category = customer.categoryId() == null ? null
                : categories.findById(customer.categoryId()).map(CustomerCategory::discountPct).orElse(null);
        try {
            return PriceResolver.resolve(
                    new Facts(product.archived(), product.salePrice(), factor, unitPrice, own, category),
                    new Request(manualUnitPrice, manualDiscountPct, Permissions.granted(PRICE_EDIT),
                            Permissions.granted(SALE_DISCOUNT)));
        } catch (PriceRefusal refusal) {
            throw switch (refusal.reason()) {
                case PRODUCT_ARCHIVED -> new BusinessException(ErrorCode.PRODUCT_ARCHIVED,
                        "This product is archived.", Map.of("productId", productId));
                case MANUAL_PRICE_NOT_ALLOWED -> new AccessDeniedException("A manual price needs price.edit");
                case DISCOUNT_ABOVE_ENTITLEMENT -> new AccessDeniedException(
                        "A discount above the entitlement needs sale.discount");
                case DISCOUNT_OUT_OF_RANGE -> new ValidationException("The discount is out of range.",
                        List.of(new Violation("manualDiscountPct", "RANGE", "Between 0 and 100.")));
            };
        }
    }
}
