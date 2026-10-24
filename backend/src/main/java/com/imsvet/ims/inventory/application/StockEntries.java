package com.imsvet.ims.inventory.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.inventory.application.LotIdentities.Found;
import com.imsvet.ims.inventory.application.StockService.Movement;
import com.imsvet.ims.inventory.domain.Lot;
import com.imsvet.ims.inventory.domain.LotStatus;
import com.imsvet.ims.inventory.domain.MovementType;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock entering lots identified by (product, lot number): goods receipts and opening stock (BR-LOT-002, ADR-070,
 * ADR-098). In the caller's transaction: finds or creates every lot under lock — existing lots in ascending id, then
 * new ones in a fixed order, so two entries never wait on each other in opposite orders (ADR-012); refuses another
 * expiry ({@code LOT_EXPIRY_MISMATCH}) and a recalled lot ({@code LOT_RECALLED}); a blocked lot is accepted and
 * stays blocked. The cost becomes the weighted average on the current balance (ADR-098), a new lot gets the
 * supplier (ADR-105), the movements go through {@link StockService}. An entry marked for blocking blocks an active
 * lot, audited {@code LOT_BLOCK} (ADR-095); when that lot already has stock, only with {@code confirmBlocking},
 * else {@code LOT_BLOCK_CONFIRMATION_REQUIRED} naming each such lot and its quantity. Callers check permissions and
 * validate their input (one entry per lot).
 */
@Service
@InternalService
public class StockEntries {

    /** One lot's entry; {@code block}: the lot is to be blocked with the batch's reason. */
    public record Entry(long productId, String lotNumber, LocalDate expiryDate, BigDecimal quantityBase,
            BigDecimal unitCost, boolean block) {
    }

    /** What the entry found and changed: before-values are {@code null} for a created lot (ADR-106). */
    public record Entered(long lotId, String lotNumber, LocalDate expiryDate, boolean created, BigDecimal costBefore,
            String statusBefore, boolean blocked, long movementId) {
    }

    /** The batch: movement type and reference, supplier for new lots, block reason and confirmation. */
    public record Batch(MovementType type, String referenceType, Long referenceId, Long supplierId,
            String blockReason, boolean confirmBlocking) {
    }

    private final LotRepository lots;
    private final LotIdentities identities;
    private final StockService stock;
    private final LotStatusChanges statusChanges;

    public StockEntries(LotRepository lots, LotIdentities identities, StockService stock, AuditRecorder audit,
            ActorProvider actors, Clock clock) {
        this.lots = lots;
        this.identities = identities;
        this.stock = stock;
        this.statusChanges = new LotStatusChanges(lots, audit, actors, clock);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Entered> enter(Batch batch, List<Entry> entries) {
        Map<String, Found> found = findOrCreate(entries);
        List<Map<String, Object>> toConfirm = new ArrayList<>();
        Map<Entry, BigDecimal> balanceBefore = new HashMap<>();
        for (Entry e : entries) {
            Lot lot = found.get(LotIdentities.key(e.productId(), e.lotNumber())).lot();
            if (lot.status() == LotStatus.RECALLED) {
                throw new BusinessException(ErrorCode.LOT_RECALLED, "This lot is recalled.",
                        Map.of("lotId", lot.id(), "lotNumber", lot.lotNumber()));
            }
            balanceBefore.put(e, lot.quantityOnHand());
            if (e.block() && lot.status() == LotStatus.ACTIVE && lot.quantityOnHand().signum() > 0
                    && !batch.confirmBlocking()) {
                Map<String, Object> pending = new LinkedHashMap<>();
                pending.put("lotId", lot.id());
                pending.put("lotNumber", lot.lotNumber());
                pending.put("productId", lot.productId());
                pending.put("quantityInStock", lot.quantityOnHand().toPlainString());
                toConfirm.add(pending);
            }
        }
        if (!toConfirm.isEmpty()) {
            throw new BusinessException(ErrorCode.LOT_BLOCK_CONFIRMATION_REQUIRED,
                    "Lots that already have stock would be blocked as a whole; confirm to block them.",
                    Map.of("lots", toConfirm));
        }
        List<Entered> results = new ArrayList<>();
        List<Movement> movements = new ArrayList<>();
        for (Entry e : entries) {
            Found f = found.get(LotIdentities.key(e.productId(), e.lotNumber()));
            Lot lot = f.lot();
            results.add(new Entered(lot.id(), lot.lotNumber(), lot.expiryDate(), f.created(),
                    f.created() ? null : lot.unitCost(), f.created() ? null : lot.status().code(), false, 0));
            lot.enterCost(e.quantityBase(), e.unitCost());
            if (f.created()) {
                lot.assignSupplier(batch.supplierId());
            }
            movements.add(new Movement(lot.id(), e.productId(), batch.type(), e.quantityBase(), null,
                    batch.referenceType(), batch.referenceId()));
        }
        List<Long> movementIds = stock.post(movements);
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            Lot lot = found.get(LotIdentities.key(e.productId(), e.lotNumber())).lot();
            boolean blocked = e.block() && lot.status() == LotStatus.ACTIVE;
            if (blocked) {
                statusChanges.change(lot, LotStatus.Action.BLOCK, AuditAction.LOT_BLOCK, batch.blockReason());
            }
            Entered r = results.get(i);
            results.set(i, new Entered(r.lotId(), r.lotNumber(), r.expiryDate(), r.created(), r.costBefore(),
                    r.statusBefore(), blocked, movementIds.get(i)));
        }
        lots.flush();
        return results;
    }

    /** Existing lots in ascending id, then new identities in key order; one lock per lot (ADR-012). */
    private Map<String, Found> findOrCreate(List<Entry> entries) {
        Map<String, Entry> byKey = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (byKey.putIfAbsent(LotIdentities.key(e.productId(), e.lotNumber()), e) != null) {
                throw new IllegalArgumentException("One entry per lot: " + e.lotNumber());
            }
        }
        Map<String, Long> existing = new HashMap<>();
        byKey.forEach((key, e) -> lots.findByIdentity(e.productId(), e.lotNumber().trim())
                .map(Lot::id).ifPresent(id -> existing.put(key, id)));
        Comparator<String> order = Comparator
                .<String, Long>comparing(key -> Optional.ofNullable(existing.get(key)).orElse(Long.MAX_VALUE))
                .thenComparing(Comparator.naturalOrder());
        Map<String, Found> found = new HashMap<>();
        for (String key : byKey.keySet().stream().sorted(order).toList()) {
            Entry e = byKey.get(key);
            found.put(key, identities.findOrCreate(e.productId(), e.lotNumber().trim(), e.expiryDate(),
                    e.unitCost()));
        }
        return found;
    }
}
