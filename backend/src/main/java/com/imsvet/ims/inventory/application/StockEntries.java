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
import com.imsvet.ims.inventory.domain.StockMovement;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import com.imsvet.ims.inventory.infrastructure.MovementReadRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
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
 * validate their input (one entry per lot). A cancelled receipt is reversed by {@link #reverseReceipt}.
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

    /**
     * The batch: movement type and reference, supplier for new lots, block reason and confirmation, and the
     * document's time recorded on a lot it blocks ({@code null}: now).
     */
    public record Batch(MovementType type, String referenceType, Long referenceId, Long supplierId,
            String blockReason, boolean confirmBlocking, Instant at) {
    }

    /** One receipt line to reverse, with what the receipt changed on its lot (ADR-106, SD-131). */
    public record Reversal(long lotId, long productId, BigDecimal quantityBase, long movementId, boolean lotCreated,
            BigDecimal lotCostBefore, boolean lotBlocked) {
    }

    /**
     * The receipt being cancelled: its reference, the block it wrote (reason, user, time) to recognise a lot still
     * in that state, and the reason of the unblocking.
     */
    public record ReceiptReversal(String referenceType, long referenceId, String blockReason, Long blockedBy,
            Instant blockedAt, String unblockReason) {
    }

    private final LotRepository lots;
    private final LotIdentities identities;
    private final StockService stock;
    private final MovementReadRepository movements;
    private final LotStatusChanges statusChanges;
    private final Clock clock;

    public StockEntries(LotRepository lots, LotIdentities identities, StockService stock,
            MovementReadRepository movements, AuditRecorder audit, ActorProvider actors, Clock clock) {
        this.lots = lots;
        this.identities = identities;
        this.stock = stock;
        this.movements = movements;
        this.statusChanges = new LotStatusChanges(lots, audit, actors, clock);
        this.clock = clock;
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
                statusChanges.change(lot, LotStatus.Action.BLOCK, AuditAction.LOT_BLOCK, batch.blockReason(),
                        batch.at() == null ? clock.instant() : batch.at());
            }
            Entered r = results.get(i);
            results.set(i, new Entered(r.lotId(), r.lotNumber(), r.expiryDate(), r.created(), r.costBefore(),
                    r.statusBefore(), blocked, movementIds.get(i)));
        }
        lots.flush();
        return results;
    }

    /**
     * Cancels a whole receipt's stock (ADR-103, ADR-106), in the caller's transaction: locks its lots in ascending id,
     * refuses with {@code RECEIPT_NOT_CANCELLABLE} when a lot has a movement after the receipt's own (naming the lot
     * and that movement), posts one {@code receipt_cancellation} per line through {@link StockService}, then per lot:
     * a lot the receipt created loses its supplier and keeps its cost (its balance is back to 0), another gets its
     * previous cost back; a lot the receipt blocked is unblocked (audited {@code LOT_UNBLOCK}) only while it is still
     * blocked with the receipt's reason, user and time. Returns the reversal movements' ids in line order.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> reverseReceipt(ReceiptReversal receipt, List<Reversal> reversals) {
        Map<Long, Lot> locked = new HashMap<>();
        lots.findAllByIdInForUpdate(reversals.stream().map(Reversal::lotId).distinct().sorted().toList())
                .forEach(l -> locked.put(l.id(), l));
        for (Reversal r : reversals) {
            List<StockMovement> later = movements.after(r.lotId(), r.movementId(), PageRequest.of(0, 1));
            if (!later.isEmpty()) {
                StockMovement m = later.getFirst();
                Map<String, Object> context = new LinkedHashMap<>();
                context.put("lotId", r.lotId());
                context.put("lotNumber", locked.get(r.lotId()).lotNumber());
                context.put("movementId", m.id());
                context.put("movementType", m.type().code());
                context.put("movementAt", m.createdAt().toString());
                throw new BusinessException(ErrorCode.RECEIPT_NOT_CANCELLABLE,
                        "A lot of this receipt has moved since; the receipt can no longer be cancelled.", context);
            }
        }
        List<Long> ids = stock.post(reversals.stream().map(r -> new Movement(r.lotId(), r.productId(),
                MovementType.RECEIPT_CANCELLATION, r.quantityBase().negate(), null, receipt.referenceType(),
                receipt.referenceId())).toList());
        for (Reversal r : reversals) {
            Lot lot = locked.get(r.lotId());
            if (r.lotCreated()) {
                lot.clearSupplier();
            } else {
                lot.restoreCost(r.lotCostBefore());
            }
            if (r.lotBlocked() && stillBlockedBy(lot, receipt)) {
                statusChanges.change(lot, LotStatus.Action.UNBLOCK, AuditAction.LOT_UNBLOCK, receipt.unblockReason());
            }
        }
        lots.flush();
        return ids;
    }

    /** ADR-106: the stored status data, not the ledger — blocked, with this receipt's reason, user and time. */
    private static boolean stillBlockedBy(Lot lot, ReceiptReversal receipt) {
        return lot.status() == LotStatus.BLOCKED && Objects.equals(lot.blockedReason(), receipt.blockReason())
                && Objects.equals(lot.blockedBy(), receipt.blockedBy())
                && Objects.equals(lot.blockedAt(), receipt.blockedAt());
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
