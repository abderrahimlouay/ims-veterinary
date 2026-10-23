package com.imsvet.ims.inventory.application;

import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.common.time.BusinessSettings;
import com.imsvet.ims.inventory.application.LotViews.MovementEntry;
import com.imsvet.ims.inventory.domain.Lot;
import com.imsvet.ims.inventory.domain.MovementType;
import com.imsvet.ims.inventory.domain.StockMovement;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import com.imsvet.ims.inventory.infrastructure.MovementReadRepository;
import com.imsvet.ims.users.application.UserNames;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger read (FR-STOCK-002, api.md §4.5): newest first; {@code from}/{@code to} are business dates in the
 * business time zone (ADR-055), both inclusive.
 */
@Service
public class MovementQueries {

    /** All optional; {@code type} is a movement type code. */
    public record MovementFilter(Long productId, Long lotId, String type, Long userId, LocalDate from, LocalDate to) {
    }

    private final MovementReadRepository movements;
    private final LotRepository lots;
    private final ProductLookup products;
    private final UserNames userNames;
    private final BusinessSettings business;

    public MovementQueries(MovementReadRepository movements, LotRepository lots, ProductLookup products,
            UserNames userNames, BusinessSettings business) {
        this.movements = movements;
        this.lots = lots;
        this.products = products;
        this.userNames = userNames;
        this.business = business;
    }

    @PreAuthorize("hasAuthority('stock.read')")
    @Transactional(readOnly = true)
    public PageResponse<MovementEntry> search(MovementFilter f, int page, int size) {
        MovementType type = type(f.type());
        Page<StockMovement> found = movements.search(f.productId(), f.lotId(), type, f.userId(), startOf(f.from()),
                f.to() == null ? null : startOf(f.to().plusDays(1)), PageRequests.of(page, size, Sort.unsorted()));
        Map<Long, Lot> lotsById = lots.findAllByIdIn(found.stream().map(StockMovement::lotId).distinct().toList())
                .stream().collect(Collectors.toMap(Lot::id, Function.identity()));
        Map<Long, ProductRef> productsById = products.findAll(found.stream().map(StockMovement::productId).distinct()
                .toList());
        Map<Long, String> names = userNames.fullNames(found.stream().map(StockMovement::userId).distinct().toList());
        return PageResponse.of(found, m -> LotViews.movement(m, lotsById.get(m.lotId()), productsById.get(m.productId()),
                names.get(m.userId())));
    }

    private Instant startOf(LocalDate date) {
        return date == null ? null : date.atStartOfDay(business.timeZone()).toInstant();
    }

    private static MovementType type(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(MovementType.values()).filter(t -> t.code().equals(code)).findFirst()
                .orElseThrow(() -> new ValidationException("The movement filter is invalid.",
                        List.of(new Violation("type", "UNKNOWN", "Unknown movement type."))));
    }
}
