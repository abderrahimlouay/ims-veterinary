package com.imsvet.ims.purchasing.application;

import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.purchasing.application.SupplierViews.PriceEntry;
import com.imsvet.ims.purchasing.application.SupplierViews.PriceProduct;
import com.imsvet.ims.purchasing.application.SupplierViews.UserRef;
import com.imsvet.ims.purchasing.domain.Supplier;
import com.imsvet.ims.purchasing.domain.SupplierPrice;
import com.imsvet.ims.purchasing.infrastructure.SupplierPriceRepository;
import com.imsvet.ims.users.application.UserNames;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A supplier's price and discount history (FR-PURCH-002, BR-PURCH-008, ADR-100): read newest first, and manual
 * entries — price per base unit ≥ 0 with at most 4 decimals (ADR-094), discount 0–100 with at most 2 decimals,
 * valid from the business date unless given. Receipts append entries from slice 5.
 */
@Service
public class SupplierPrices {

    public record PriceInput(Long productId, BigDecimal unitPrice, BigDecimal discountPct, LocalDate validFrom) {
    }

    private static final int PRICE_SCALE = 4;
    private static final int DISCOUNT_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final SupplierPriceRepository prices;
    private final SupplierDirectory directory;
    private final ProductLookup products;
    private final UserNames userNames;
    private final ActorProvider actors;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public SupplierPrices(SupplierPriceRepository prices, SupplierDirectory directory, ProductLookup products,
            UserNames userNames, ActorProvider actors, BusinessCalendar calendar, Clock clock) {
        this.prices = prices;
        this.directory = directory;
        this.products = products;
        this.userNames = userNames;
        this.actors = actors;
        this.calendar = calendar;
        this.clock = clock;
    }

    @PreAuthorize("hasAuthority('supplier.read')")
    @Transactional(readOnly = true)
    public PageResponse<PriceEntry> history(long supplierId, Long productId, int page, int size) {
        directory.find(supplierId);
        Page<SupplierPrice> found = prices.history(supplierId, productId, PageRequests.of(page, size, Sort.unsorted()));
        Map<Long, ProductRef> refs = products.findAll(found.map(SupplierPrice::productId).toSet());
        Map<Long, String> names = userNames.fullNames(found.map(SupplierPrice::createdBy).toSet());
        return PageResponse.of(found, p -> entry(p, refs.get(p.productId()), names));
    }

    /** A product must exist and not be archived (it can no longer be ordered, BR-PROD-004). */
    @PreAuthorize("hasAuthority('supplier.manage')")
    @Transactional
    public PriceEntry add(long supplierId, PriceInput in) {
        Supplier supplier = directory.find(supplierId);
        if (in.productId() == null) {
            throw invalid("productId", "REQUIRED", "The product is required.");
        }
        ProductRef product = products.find(in.productId())
                .orElseThrow(() -> invalid("productId", "UNKNOWN", "Unknown product."));
        if (product.archived()) {
            throw new BusinessException(ErrorCode.PRODUCT_ARCHIVED, "This product is archived.",
                    Map.of("productId", product.id()));
        }
        BigDecimal price = price(in.unitPrice());
        BigDecimal discount = discount(in.discountPct());
        LocalDate validFrom = in.validFrom() == null ? calendar.today() : in.validFrom();
        Long userId = actors.current().userId();
        SupplierPrice saved = prices.saveAndFlush(new SupplierPrice(supplier.id(), product.id(), price, discount,
                validFrom, userId, clock.instant()));
        return entry(saved, product, userNames.fullNames(List.of(userId)));
    }

    private static BigDecimal price(BigDecimal value) {
        if (value == null) {
            throw invalid("unitPrice", "REQUIRED", "The price is required.");
        }
        if (value.signum() < 0) {
            throw invalid("unitPrice", "NOT_NEGATIVE", "A price cannot be negative.");
        }
        if (value.stripTrailingZeros().scale() > PRICE_SCALE) {
            throw invalid("unitPrice", "SCALE", "At most four decimals.");
        }
        return value.setScale(PRICE_SCALE);
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

    private static PriceEntry entry(SupplierPrice p, ProductRef product, Map<Long, String> names) {
        return new PriceEntry(p.id(), new PriceProduct(product.id(), product.sku(), product.commercialName(),
                product.baseUnit()), p.unitPrice(), p.discountPct(), p.validFrom(), p.createdAt(),
                new UserRef(p.createdBy(), names.get(p.createdBy())));
    }

    private static ValidationException invalid(String field, String code, String message) {
        return new ValidationException("The price entry is invalid.", List.of(new Violation(field, code, message)));
    }
}
