package com.imsvet.ims.inventory.application;

import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.inventory.application.CountViews.CountDetail;
import com.imsvet.ims.inventory.application.CountViews.Line;
import com.imsvet.ims.inventory.domain.CountLine;
import com.imsvet.ims.inventory.domain.CountStatus;
import com.imsvet.ims.inventory.domain.CountType;
import com.imsvet.ims.inventory.domain.InventoryCount;
import com.imsvet.ims.inventory.domain.Lot;
import com.imsvet.ims.inventory.infrastructure.CountLineRepository;
import com.imsvet.ims.inventory.infrastructure.InventoryCountRepository;
import com.imsvet.ims.inventory.infrastructure.LotLocks;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import com.imsvet.ims.inventory.infrastructure.MovementReadRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory counts before regularisation (W5, FR-STOCK-006): opening a full or cycle count (BR-INV-003/007,
 * ADR-073), entering counted quantities with a watermark (BR-INV-001/008, ADR-072), adding a lot without a line or a
 * lot unknown to the system (ADR-074), and cancelling. Nothing here moves stock; only an open count changes
 * (BR-INV-004). The count is read {@code FOR SHARE} (lines) or locked {@code FOR UPDATE} (added lines, cancel)
 * before any lot (ADR-012).
 */
@Service
public class InventoryCounts {

    public record OpenInput(String type, Integer categoryId, List<Long> productIds, String notes) {
    }

    /** An existing lot ({@code lotId}) or a lot unknown to the system (the other fields, ADR-074). */
    public record AddInput(Long lotId, Long productId, String lotNumber, LocalDate expiryDate, BigDecimal unitCost,
            BigDecimal countedQty) {
    }

    private static final int QUANTITY_SCALE = 3;
    private static final int COST_SCALE = 4;
    private static final int LOT_NUMBER_MAX = 60;

    private final InventoryCountRepository counts;
    private final CountLineRepository lines;
    private final LotRepository lots;
    private final LotLocks lotLocks;
    private final MovementReadRepository movements;
    private final ProductLookup products;
    private final CountQueries queries;
    private final ActorProvider actors;
    private final Clock clock;

    public InventoryCounts(InventoryCountRepository counts, CountLineRepository lines, LotRepository lots,
            LotLocks lotLocks,
            MovementReadRepository movements, ProductLookup products, CountQueries queries, ActorProvider actors,
            Clock clock) {
        this.counts = counts;
        this.lines = lines;
        this.lots = lots;
        this.lotLocks = lotLocks;
        this.movements = movements;
        this.products = products;
        this.queries = queries;
        this.actors = actors;
        this.clock = clock;
    }

    /** One line per lot with stock in scope, its balance as theoretical quantity (informational, ADR-072). */
    @PreAuthorize("hasAuthority('inventory.count')")
    @Transactional
    public CountDetail open(OpenInput in) {
        CountType type = CountType.fromCode(in.type()).orElseThrow(() -> invalid("type",
                in.type() == null ? "REQUIRED" : "UNKNOWN", "Full or cycle."));
        Long userId = actors.current().userId();
        String notes = in.notes() == null || in.notes().isBlank() ? null : in.notes().trim();
        boolean byCategory = in.categoryId() != null;
        boolean byProducts = in.productIds() != null && !in.productIds().isEmpty();
        InventoryCount count;
        List<Lot> inScope;
        if (type == CountType.FULL) {
            if (byCategory || byProducts) {
                throw invalid("scope", "NOT_ALLOWED", "A full count covers every lot with stock.");
            }
            if (counts.existsByTypeAndStatus(CountType.FULL, CountStatus.OPEN)) {
                throw alreadyOpen();
            }
            count = InventoryCount.full(notes, userId, clock.instant());
            inScope = lots.findWithStock();
        } else if (byCategory == byProducts) {
            throw byCategory ? invalid("scope", "ONE_OF", "A category or a product list, not both.")
                    : invalid("scope", "REQUIRED", "A category or a product list.");
        } else if (byCategory) {
            if (!products.categoryExists(in.categoryId())) {
                throw invalid("categoryId", "UNKNOWN", "Unknown category.");
            }
            count = InventoryCount.cycleOfCategory(in.categoryId(), notes, userId, clock.instant());
            List<Long> productIds = products.idsInCategory(in.categoryId());
            inScope = productIds.isEmpty() ? List.of() : lots.findWithStockOfProducts(productIds);
        } else {
            Set<Long> productIds = new LinkedHashSet<>(in.productIds());
            if (productIds.contains(null) || products.findAll(productIds).size() != productIds.size()) {
                throw invalid("productIds", "UNKNOWN", "Unknown product.");
            }
            count = InventoryCount.cycleOfProducts(productIds, notes, userId, clock.instant());
            inScope = lots.findWithStockOfProducts(productIds);
        }
        try {
            count = counts.saveAndFlush(count);
        } catch (DataIntegrityViolationException e) {
            throw alreadyOpen();
        }
        List<CountLine> created = new ArrayList<>();
        for (Lot lot : inScope) {
            created.add(CountLine.ofLot(count.id(), lot.productId(), lot.id(), lot.quantityOnHand()));
        }
        lines.saveAll(created);
        return queries.detail(count);
    }

    /** Locks the lot and takes a new watermark (ADR-072, BR-INV-008). */
    @PreAuthorize("hasAuthority('inventory.count')")
    @Transactional
    public Line enter(long countId, long lineId, BigDecimal countedQty) {
        openCount(counts.findByIdForShare(countId), countId);
        CountLine line = lines.findByIdAndCountId(lineId, countId).orElseThrow(() -> new BusinessException(
                ErrorCode.NOT_FOUND, "Count line not found.", Map.of("id", lineId)));
        if (line.regularized()) {
            throw new BusinessException(ErrorCode.INVENTORY_LINE_ALREADY_REGULARIZED, "This line is regularised.",
                    Map.of("lineId", lineId));
        }
        ProductRef product = products.find(line.productId()).orElseThrow();
        BigDecimal quantity = counted(countedQty, product.divisible(), line.lotId() == null);
        record(line, quantity, line.lotId() == null ? null : lotLocks.lock(line.lotId()).orElseThrow());
        return queries.line(lines.saveAndFlush(line));
    }

    /**
     * ADR-074: an existing lot of a product in scope without a line, counted at once; or a lot unknown to the
     * system, created at regularisation. A found lot number that exists with the same expiry is that lot.
     */
    @PreAuthorize("hasAuthority('inventory.count')")
    @Transactional
    public Line add(long countId, AddInput in) {
        InventoryCount count = openCount(counts.findByIdForUpdate(countId), countId);
        Lot lot;
        if (in.lotId() != null) {
            lot = lots.findById(in.lotId()).orElseThrow(() -> invalid("lotId", "UNKNOWN", "Unknown lot."));
        } else {
            String lotNumber = validateFound(in);
            if (products.find(in.productId()).isEmpty()) {
                throw invalid("productId", "UNKNOWN", "Unknown product.");
            }
            lot = lots.findByIdentity(in.productId(), lotNumber).orElse(null);
            if (lot != null && !lot.expiryDate().equals(in.expiryDate())) {
                throw new BusinessException(ErrorCode.LOT_EXPIRY_MISMATCH,
                        "This lot number already exists with another expiry date.",
                        Map.of("lotId", lot.id(), "expiryDate", lot.expiryDate().toString()));
            }
            if (lot == null) {
                return addFound(count, in, lotNumber);
            }
        }
        requireInScope(count, lot.productId());
        if (lines.existsByCountIdAndLotId(countId, lot.id())) {
            throw invalid("lotId", "DUPLICATE", "This lot already has a line.");
        }
        ProductRef product = products.find(lot.productId()).orElseThrow();
        BigDecimal quantity = counted(in.countedQty(), product.divisible(), false);
        Lot locked = lotLocks.lock(lot.id()).orElseThrow();
        CountLine line = CountLine.ofLot(countId, locked.productId(), locked.id(), locked.quantityOnHand());
        record(line, quantity, locked);
        return queries.line(lines.saveAndFlush(line));
    }

    /** No movement (ADR-075). */
    @PreAuthorize("hasAuthority('inventory.validate')")
    @Transactional
    public CountDetail cancel(long countId) {
        InventoryCount count = openCount(counts.findByIdForUpdate(countId), countId);
        count.cancel(actors.current().userId(), clock.instant());
        return queries.detail(counts.saveAndFlush(count));
    }

    private Line addFound(InventoryCount count, AddInput in, String lotNumber) {
        requireInScope(count, in.productId());
        if (lines.existsByCountIdAndProductIdAndFoundLotNumber(count.id(), in.productId(), lotNumber)) {
            throw invalid("lotNumber", "DUPLICATE", "This lot already has a line.");
        }
        ProductRef product = products.find(in.productId()).orElseThrow();
        BigDecimal quantity = counted(in.countedQty(), product.divisible(), true);
        CountLine line = CountLine.ofFoundLot(count.id(), product.id(), lotNumber, in.expiryDate(),
                in.unitCost().setScale(COST_SCALE));
        record(line, quantity, null);
        return queries.line(lines.saveAndFlush(line));
    }

    /** {@code lot} locked, or null for a found lot: balance 0, no watermark. */
    private void record(CountLine line, BigDecimal quantity, Lot lot) {
        BigDecimal balance = lot == null ? BigDecimal.ZERO.setScale(QUANTITY_SCALE) : lot.quantityOnHand();
        Long watermark = lot == null ? null : movements.lastIdOfLot(lot.id());
        line.count(quantity, balance, watermark, actors.current().userId(), clock.instant());
    }

    private void requireInScope(InventoryCount count, long productId) {
        boolean inScope = switch (count.type()) {
            case FULL -> true;
            case CYCLE -> count.scopeCategoryId() != null
                    ? Optional.of(count.scopeCategoryId()).equals(products.categoryOf(productId))
                    : count.scopeProductIds().contains(productId);
        };
        if (!inScope) {
            throw invalid("productId", "OUT_OF_SCOPE", "This product is not in the count's scope.");
        }
    }

    private static InventoryCount openCount(Optional<InventoryCount> found, long countId) {
        InventoryCount count = found.orElseThrow(() -> CountQueries.notFound(countId));
        if (!count.open()) {
            throw new BusinessException(ErrorCode.INVENTORY_NOT_OPEN, "This count is " + count.status().code() + ".",
                    Map.of("countId", countId));
        }
        return count;
    }

    /** ≥ 0 (> 0 for a found lot), at most 3 decimals, whole for a non-divisible product (ADR-050). */
    private static BigDecimal counted(BigDecimal quantity, boolean divisible, boolean found) {
        if (quantity == null) {
            throw invalid("countedQty", "REQUIRED", "The counted quantity is required.");
        }
        if (found && quantity.signum() <= 0) {
            throw invalid("countedQty", "POSITIVE", "A lot found during the count has a quantity above zero.");
        }
        if (quantity.signum() < 0) {
            throw invalid("countedQty", "NOT_NEGATIVE", "A counted quantity cannot be negative.");
        }
        if (quantity.stripTrailingZeros().scale() > QUANTITY_SCALE) {
            throw invalid("countedQty", "SCALE", "At most three decimals.");
        }
        if (!divisible && quantity.stripTrailingZeros().scale() > 0) {
            throw invalid("countedQty", "WHOLE_NUMBER", "This product is counted in whole units.");
        }
        return quantity.setScale(QUANTITY_SCALE);
    }

    /** Returns the trimmed lot number (BR-LOT-001). */
    private static String validateFound(AddInput in) {
        List<Violation> violations = new ArrayList<>();
        if (in.productId() == null) {
            violations.add(new Violation("productId", "REQUIRED", "The product is required."));
        }
        String lotNumber = in.lotNumber() == null ? "" : in.lotNumber().trim();
        if (lotNumber.isEmpty()) {
            violations.add(new Violation("lotNumber", "REQUIRED", "The lot number is required."));
        } else if (lotNumber.length() > LOT_NUMBER_MAX) {
            violations.add(new Violation("lotNumber", "SIZE", "At most 60 characters."));
        }
        if (in.expiryDate() == null) {
            violations.add(new Violation("expiryDate", "REQUIRED", "The expiry date is required."));
        }
        if (in.unitCost() == null) {
            violations.add(new Violation("unitCost", "REQUIRED", "The unit cost is required."));
        } else if (in.unitCost().signum() < 0) {
            violations.add(new Violation("unitCost", "NOT_NEGATIVE", "A cost cannot be negative."));
        } else if (in.unitCost().stripTrailingZeros().scale() > COST_SCALE) {
            violations.add(new Violation("unitCost", "SCALE", "At most four decimals."));
        }
        if (!violations.isEmpty()) {
            throw new ValidationException("The count line is invalid.", violations);
        }
        return lotNumber;
    }

    private static BusinessException alreadyOpen() {
        return new BusinessException(ErrorCode.INVENTORY_ALREADY_OPEN, "A full count is already open.");
    }

    private static ValidationException invalid(String field, String code, String message) {
        return new ValidationException("The count is invalid.", List.of(new Violation(field, code, message)));
    }
}
