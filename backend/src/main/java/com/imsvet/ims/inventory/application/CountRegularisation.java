package com.imsvet.ims.inventory.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.inventory.application.CountViews.CountDetail;
import com.imsvet.ims.inventory.application.LotIdentities.Found;
import com.imsvet.ims.inventory.application.StockService.Movement;
import com.imsvet.ims.inventory.domain.CountLine;
import com.imsvet.ims.inventory.domain.InventoryCount;
import com.imsvet.ims.inventory.domain.Lot;
import com.imsvet.ims.inventory.domain.MovementType;
import com.imsvet.ims.inventory.infrastructure.CountLineRepository;
import com.imsvet.ims.inventory.infrastructure.InventoryCountRepository;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regularising a count (W5, ADR-075, BR-INV-002/006/008), all or nothing in one transaction: the count is locked,
 * then every affected lot in ascending id order (ADR-012); lots found during the count are created after them, so
 * in the same ascending order (ADR-074). Each lot's final balance (current balance + variance, ADR-072) is checked
 * before anything is posted: one negative refuses everything with {@code STOCK_INSUFFICIENT} naming the line, and
 * the count stays open. A non-zero variance posts one {@code adjustment_inventory} (reason {@code Inventory #<id>})
 * through {@link StockService} and audits {@code STOCK_CORRECTION} with the old and new lot quantity; a zero variance
 * posts nothing. The accepted lines are flagged regularised and the count closes.
 */
@Service
public class CountRegularisation {

    private static final String REFERENCE = "inventory";

    private final InventoryCountRepository counts;
    private final CountLineRepository lines;
    private final LotRepository lots;
    private final LotIdentities identities;
    private final StockService stock;
    private final AuditRecorder audit;
    private final CountQueries queries;
    private final ActorProvider actors;
    private final Clock clock;

    public CountRegularisation(InventoryCountRepository counts, CountLineRepository lines, LotRepository lots,
            LotIdentities identities, StockService stock, AuditRecorder audit, CountQueries queries,
            ActorProvider actors, Clock clock) {
        this.counts = counts;
        this.lines = lines;
        this.lots = lots;
        this.identities = identities;
        this.stock = stock;
        this.audit = audit;
        this.queries = queries;
        this.actors = actors;
        this.clock = clock;
    }

    /** {@code lineIds} null or empty: every counted line not yet regularised. */
    @PreAuthorize("hasAuthority('inventory.validate')")
    @Transactional
    public CountDetail regularize(long countId, List<Long> lineIds) {
        InventoryCount count = counts.findByIdForUpdate(countId).orElseThrow(() -> CountQueries.notFound(countId));
        List<CountLine> accepted = accepted(count, lineIds);
        Map<Long, Long> existingLotOfFound = resolveFound(countId, accepted);
        Map<Long, Lot> locked = lockLots(accepted, existingLotOfFound);
        refuseNegative(accepted, existingLotOfFound, locked);
        Map<Long, Boolean> created = new HashMap<>();
        for (CountLine line : accepted) {
            if (line.lotId() == null) {
                Long existing = existingLotOfFound.get(line.id());
                Lot lot = existing != null ? locked.get(existing) : createFoundLot(line, created);
                locked.putIfAbsent(lot.id(), lot);
                line.attachLot(lot.id());
            }
        }
        String reason = "Inventory #" + countId;
        List<CountLine> byLot = accepted.stream().sorted(Comparator.comparing(CountLine::lotId)).toList();
        for (CountLine line : byLot) {
            BigDecimal variance = line.varianceQty();
            if (variance.signum() == 0) {
                line.regularize(null);
                continue;
            }
            Lot lot = locked.get(line.lotId());
            BigDecimal before = lot.quantityOnHand();
            long movementId = stock.post(List.of(new Movement(lot.id(), lot.productId(),
                    MovementType.ADJUSTMENT_INVENTORY, variance, reason, REFERENCE, countId))).getFirst();
            line.regularize(movementId);
            Map<String, Object> newValues = new LinkedHashMap<>();
            newValues.put("quantity", lot.quantityOnHand().toPlainString());
            newValues.put("type", MovementType.ADJUSTMENT_INVENTORY.code());
            newValues.put("reason", reason);
            newValues.put("countId", countId);
            newValues.put("lineId", line.id());
            newValues.put("movementId", movementId);
            newValues.put("created", created.getOrDefault(lot.id(), false));
            audit.record(AuditAction.STOCK_CORRECTION, "lots", lot.id(), Map.of("quantity", before.toPlainString()),
                    newValues);
        }
        count.close(actors.current().userId(), clock.instant());
        return queries.detail(counts.saveAndFlush(count));
    }

    /**
     * Unknown ids are a validation error; a line already regularised is refused before the count's status, so a
     * repeated call naming it answers {@code INVENTORY_LINE_ALREADY_REGULARIZED} (BR-INV-004).
     */
    private List<CountLine> accepted(InventoryCount count, List<Long> lineIds) {
        List<CountLine> all = lines.findByCountIdOrderById(count.id());
        if (lineIds == null || lineIds.isEmpty()) {
            requireOpen(count);
            return all.stream().filter(l -> l.countedQty() != null && !l.regularized()).toList();
        }
        Map<Long, CountLine> byId = all.stream().collect(Collectors.toMap(CountLine::id, Function.identity()));
        List<CountLine> accepted = new ArrayList<>();
        for (Long id : lineIds.stream().distinct().toList()) {
            CountLine line = byId.get(id);
            if (line == null) {
                throw invalid("lineIds", "UNKNOWN", "Not a line of this count.");
            }
            if (line.regularized()) {
                throw new BusinessException(ErrorCode.INVENTORY_LINE_ALREADY_REGULARIZED, "This line is regularised.",
                        Map.of("lineId", id));
            }
            accepted.add(line);
        }
        requireOpen(count);
        if (accepted.stream().anyMatch(l -> l.countedQty() == null)) {
            throw invalid("lineIds", "NOT_COUNTED", "Only counted lines can be regularised.");
        }
        return accepted;
    }

    /**
     * A found lot whose number now exists for the product (ignoring case and accents) is that lot when the expiry
     * matches, else {@code LOT_EXPIRY_MISMATCH} (ADR-070, ADR-074), before any lock or posting. Returns line id →
     * existing lot id.
     */
    private Map<Long, Long> resolveFound(long countId, List<CountLine> accepted) {
        Map<Long, Long> existing = new HashMap<>();
        for (CountLine line : accepted) {
            if (line.lotId() != null) {
                continue;
            }
            Lot lot = lots.findByIdentity(line.productId(), line.foundLotNumber()).orElse(null);
            if (lot == null) {
                continue;
            }
            if (!lot.expiryDate().equals(line.foundExpiryDate())) {
                throw new BusinessException(ErrorCode.LOT_EXPIRY_MISMATCH,
                        "This lot number now exists with another expiry date.",
                        Map.of("lineId", line.id(), "lotId", lot.id(), "expiryDate", lot.expiryDate().toString()));
            }
            if (lines.existsByCountIdAndLotId(countId, lot.id())) {
                throw invalid("lineIds", "DUPLICATE", "This found lot now has another line in the count.");
            }
            existing.put(line.id(), lot.id());
        }
        return existing;
    }

    private Map<Long, Lot> lockLots(List<CountLine> accepted, Map<Long, Long> existingLotOfFound) {
        List<Long> ids = accepted.stream().map(l -> l.lotId() != null ? l.lotId() : existingLotOfFound.get(l.id()))
                .filter(id -> id != null).distinct().sorted().toList();
        Map<Long, Lot> locked = new HashMap<>();
        if (!ids.isEmpty()) {
            lots.findAllByIdInForUpdate(ids).forEach(lot -> locked.put(lot.id(), lot));
        }
        return locked;
    }

    /** Current balance + variance per lot (ADR-072); a new found lot only gains its counted quantity. */
    private static void refuseNegative(List<CountLine> accepted, Map<Long, Long> existingLotOfFound,
            Map<Long, Lot> locked) {
        Map<Long, BigDecimal> after = new HashMap<>();
        for (CountLine line : accepted) {
            Long lotId = line.lotId() != null ? line.lotId() : existingLotOfFound.get(line.id());
            if (lotId == null) {
                continue;
            }
            Lot lot = locked.get(lotId);
            BigDecimal balance = after.getOrDefault(lotId, lot.quantityOnHand()).add(line.varianceQty());
            if (balance.signum() < 0) {
                Map<String, Object> context = new LinkedHashMap<>();
                context.put("lineId", line.id());
                context.put("lotId", lotId);
                context.put("available", lot.quantityOnHand().toPlainString());
                context.put("variance", line.varianceQty().toPlainString());
                throw new BusinessException(ErrorCode.STOCK_INSUFFICIENT,
                        "Regularising this line would make the lot balance negative.", context);
            }
            after.put(lotId, balance);
        }
    }

    private Lot createFoundLot(CountLine line, Map<Long, Boolean> created) {
        Found found = identities.findOrCreate(line.productId(), line.foundLotNumber(), line.foundExpiryDate(),
                line.foundUnitCost());
        created.put(found.lot().id(), found.created());
        return found.lot();
    }

    private static void requireOpen(InventoryCount count) {
        if (!count.open()) {
            throw new BusinessException(ErrorCode.INVENTORY_NOT_OPEN, "This count is " + count.status().code() + ".",
                    Map.of("countId", count.id()));
        }
    }

    private static ValidationException invalid(String field, String code, String message) {
        return new ValidationException("The regularisation is invalid.", List.of(new Violation(field, code, message)));
    }
}
