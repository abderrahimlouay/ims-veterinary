package com.imsvet.ims.inventory.application;

import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.inventory.application.LotViews.LotDetail;
import com.imsvet.ims.inventory.application.LotViews.LotSummary;
import com.imsvet.ims.inventory.domain.EffectiveLotStatus;
import com.imsvet.ims.inventory.domain.ExpiryPolicy;
import com.imsvet.ims.inventory.domain.Lot;
import com.imsvet.ims.inventory.domain.LotStatus;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lot reads (api.md §4.4, FR-LOT-004/005/007): effective status, days left and alert level computed with the
 * current expiry policy on every query (ADR-055, ADR-056, ADR-059). There is no write here (ADR-064, BR-LOT-011).
 */
@Service
public class LotQueries {

    /** The filters of {@code GET /api/lots}; all optional. {@code status} is an effective status code. */
    public record LotFilter(Long productId, String status, Integer expiringWithinDays, boolean hasStock, String q) {
    }

    private final LotRepository lots;
    private final ProductLookup products;
    private final ExpiryPolicies policies;

    public LotQueries(LotRepository lots, ProductLookup products, ExpiryPolicies policies) {
        this.lots = lots;
        this.products = products;
        this.policies = policies;
    }

    @PreAuthorize("hasAuthority('lot.read')")
    @Transactional(readOnly = true)
    public PageResponse<LotSummary> search(LotFilter filter, int page, int size) {
        ExpiryPolicy policy = policies.current();
        EffectiveLotStatus status = status(filter.status());
        if (filter.expiringWithinDays() != null && filter.expiringWithinDays() < 0) {
            throw invalid("expiringWithinDays", "RANGE", "The number of days cannot be negative.");
        }
        String term = filter.q() == null || filter.q().isBlank() ? null : filter.q().trim();
        Page<Lot> found = lots.search(filter.productId(), stored(status), expired(status), withStock(status),
                filter.hasStock(),
                filter.expiringWithinDays() == null ? null
                        : policy.businessDate().plusDays(filter.expiringWithinDays()),
                term == null ? null : "%" + term.replaceAll("([!%_])", "!$1") + "%", policy.businessDate(),
                PageRequests.of(page, size, Sort.unsorted()));
        Map<Long, ProductRef> refs = products.findAll(found.stream().map(Lot::productId).distinct().toList());
        return PageResponse.of(found, l -> LotViews.summary(l, refs.get(l.productId()), policy));
    }

    @PreAuthorize("hasAuthority('lot.read')")
    @Transactional(readOnly = true)
    public LotDetail get(long id) {
        Lot lot = lots.findById(id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Lot not found.", Map.of("id", id)));
        ProductRef product = products.find(lot.productId()).orElseThrow();
        return LotViews.detail(lot, product, policies.current());
    }

    /** All lots of the product in FEFO order: expiry date, then id (BR-LOT-005). */
    @PreAuthorize("hasAuthority('lot.read')")
    @Transactional(readOnly = true)
    public List<LotSummary> ofProduct(long productId) {
        ProductRef product = products.find(productId).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Product not found.", Map.of("id", productId)));
        ExpiryPolicy policy = policies.current();
        return lots.findByProductIdInFefoOrder(productId).stream().map(l -> LotViews.summary(l, product, policy))
                .toList();
    }

    private static EffectiveLotStatus status(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(EffectiveLotStatus.values()).filter(s -> s.code().equals(code)).findFirst()
                .orElseThrow(() -> invalid("status", "UNKNOWN", "Unknown lot status."));
    }

    private static LotStatus stored(EffectiveLotStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case RECALLED -> LotStatus.RECALLED;
            case BLOCKED -> LotStatus.BLOCKED;
            case EXPIRED, DEPLETED, ACTIVE -> LotStatus.ACTIVE;
        };
    }

    private static Boolean expired(EffectiveLotStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case EXPIRED -> true;
            case DEPLETED, ACTIVE -> false;
            case RECALLED, BLOCKED -> null;
        };
    }

    private static Boolean withStock(EffectiveLotStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case ACTIVE -> true;
            case DEPLETED -> false;
            case RECALLED, BLOCKED, EXPIRED -> null;
        };
    }

    private static ValidationException invalid(String field, String code, String message) {
        return new ValidationException("The lot filter is invalid.", List.of(new Violation(field, code, message)));
    }
}
