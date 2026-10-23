package com.imsvet.ims.inventory.application;

import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.inventory.application.CountViews.CountDetail;
import com.imsvet.ims.inventory.application.CountViews.CountSummary;
import com.imsvet.ims.inventory.application.CountViews.FoundLot;
import com.imsvet.ims.inventory.application.CountViews.Line;
import com.imsvet.ims.inventory.application.CountViews.LineLot;
import com.imsvet.ims.inventory.application.CountViews.LineProduct;
import com.imsvet.ims.inventory.application.CountViews.Scope;
import com.imsvet.ims.inventory.application.CountViews.UserRef;
import com.imsvet.ims.inventory.domain.CountLine;
import com.imsvet.ims.inventory.domain.CountStatus;
import com.imsvet.ims.inventory.domain.InventoryCount;
import com.imsvet.ims.inventory.domain.Lot;
import com.imsvet.ims.inventory.infrastructure.CountLineRepository;
import com.imsvet.ims.inventory.infrastructure.InventoryCountRepository;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import com.imsvet.ims.users.application.UserNames;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Count list and detail (api.md §4.5), with each line's variance and the movements since its watermark (ADR-072). */
@Service
public class CountQueries {

    private static final int QUANTITY_SCALE = 3;

    private final InventoryCountRepository counts;
    private final CountLineRepository lines;
    private final LotRepository lots;
    private final ProductLookup products;
    private final UserNames userNames;

    public CountQueries(InventoryCountRepository counts, CountLineRepository lines, LotRepository lots,
            ProductLookup products, UserNames userNames) {
        this.counts = counts;
        this.lines = lines;
        this.lots = lots;
        this.products = products;
        this.userNames = userNames;
    }

    @PreAuthorize("hasAuthority('inventory.count')")
    @Transactional(readOnly = true)
    public PageResponse<CountSummary> search(String status, int page, int size) {
        CountStatus wanted = status == null || status.isBlank() ? null : CountStatus.fromCode(status)
                .orElseThrow(() -> new ValidationException("The count filter is invalid.",
                        List.of(new Violation("status", "UNKNOWN", "Open, closed or cancelled."))));
        Page<InventoryCount> found = counts.search(wanted, PageRequests.of(page, size, Sort.unsorted()));
        Map<Long, long[]> tallies = new HashMap<>();
        if (!found.isEmpty()) {
            for (Object[] row : lines.tallies(found.map(InventoryCount::id).toList())) {
                tallies.put((Long) row[0], new long[] {((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
            }
        }
        Map<Long, String> names = userNames.fullNames(found.map(InventoryCount::startedBy).toSet());
        return PageResponse.of(found, c -> {
            long[] tally = tallies.getOrDefault(c.id(), new long[2]);
            return new CountSummary(c.id(), c.type().code(), c.status().code(), c.startedAt(),
                    user(c.startedBy(), names), c.closedAt(), tally[0], tally[1]);
        });
    }

    @PreAuthorize("hasAuthority('inventory.count')")
    @Transactional(readOnly = true)
    public CountDetail detail(long id) {
        return detail(counts.findById(id).orElseThrow(() -> notFound(id)));
    }

    /** In the caller's transaction, after its changes. */
    CountDetail detail(InventoryCount count) {
        List<CountLine> countLines = lines.findByCountIdOrderById(count.id());
        Map<Long, String> names = userNames.fullNames(Stream.concat(Stream.of(count.startedBy(), count.closedBy()),
                countLines.stream().map(CountLine::countedBy)).filter(Objects::nonNull).collect(Collectors.toSet()));
        Function<CountLine, Line> view = lineViews(count.id(), countLines, names);
        Scope scope = new Scope(count.scopeCategoryId(), count.scopeProductIds().stream().sorted().toList());
        return new CountDetail(count.id(), count.type().code(), count.status().code(), scope, count.notes(),
                count.startedAt(), user(count.startedBy(), names), count.closedAt(), user(count.closedBy(), names),
                count.version(), countLines.stream().map(view).toList());
    }

    /** In the caller's transaction, after its changes. */
    Line line(CountLine line) {
        Set<Long> users = line.countedBy() == null ? Set.of() : Set.of(line.countedBy());
        return lineViews(line.countId(), List.of(line), userNames.fullNames(users)).apply(line);
    }

    private Function<CountLine, Line> lineViews(long countId, Collection<CountLine> countLines,
            Map<Long, String> names) {
        Set<Long> lotIds = new HashSet<>();
        Set<Long> productIds = new HashSet<>();
        for (CountLine l : countLines) {
            productIds.add(l.productId());
            if (l.lotId() != null) {
                lotIds.add(l.lotId());
            }
        }
        Map<Long, ProductRef> productRefs = products.findAll(productIds);
        Map<Long, Lot> lotsById = lotIds.isEmpty() ? Map.of() : lots.findAllByIdIn(lotIds).stream()
                .collect(Collectors.toMap(Lot::id, Function.identity()));
        Map<Long, BigDecimal> since = new HashMap<>();
        for (Object[] row : lines.movementsSinceCount(countId)) {
            since.put((Long) row[0], (BigDecimal) row[1]);
        }
        return l -> {
            ProductRef p = productRefs.get(l.productId());
            Lot lot = l.lotId() == null ? null : lotsById.get(l.lotId());
            BigDecimal movements = l.countedQty() == null || lot == null ? null
                    : since.getOrDefault(l.id(), BigDecimal.ZERO).setScale(QUANTITY_SCALE);
            return new Line(l.id(), new LineProduct(p.id(), p.sku(), p.commercialName(), p.baseUnit()),
                    lot == null ? null : new LineLot(lot.id(), lot.lotNumber(), lot.expiryDate()),
                    lot == null ? new FoundLot(l.foundLotNumber(), l.foundExpiryDate(), l.foundUnitCost()) : null,
                    l.theoreticalQty(), l.countedQty(), l.countedAt(), user(l.countedBy(), names),
                    l.balanceAtCount(), l.varianceQty(), movements, lot == null ? null : lot.quantityOnHand(),
                    l.regularized(), l.adjustmentMovementId());
        };
    }

    private static UserRef user(Long id, Map<Long, String> names) {
        return id == null ? null : new UserRef(id, names.get(id));
    }

    static BusinessException notFound(long id) {
        return new BusinessException(ErrorCode.NOT_FOUND, "Count not found.", Map.of("id", id));
    }
}
