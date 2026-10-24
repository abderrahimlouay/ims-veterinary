package com.imsvet.ims.inventory.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.inventory.application.LotViews.LotDetail;
import com.imsvet.ims.inventory.application.LotViews.RecallReport;
import com.imsvet.ims.inventory.domain.Lot;
import com.imsvet.ims.inventory.domain.LotStatus;
import com.imsvet.ims.inventory.domain.LotStatus.Action;
import com.imsvet.ims.inventory.infrastructure.LotLocks;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import com.imsvet.ims.users.application.UserNames;
import java.time.Clock;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Block, unblock and recall (BR-LOT-008/009, ADR-060, W6). Each locks the lot row, refuses a transition outside the
 * table ({@code INVALID_STATE_TRANSITION}), writes its audit entry in the same transaction before changing the lot
 * (BR-AUDIT-003), then records reason, user and time. A repeated recall returns the same report and changes nothing.
 */
@Service
public class LotActions {

    private final LotRepository lots;
    private final LotLocks lotLocks;
    private final ProductLookup products;
    private final ExpiryPolicies policies;
    private final LotStatusChanges statusChanges;
    private final UserNames userNames;

    public LotActions(LotRepository lots,
            LotLocks lotLocks, ProductLookup products, ExpiryPolicies policies, AuditRecorder audit,
            ActorProvider actors, Clock clock, UserNames userNames) {
        this.lots = lots;
        this.lotLocks = lotLocks;
        this.products = products;
        this.policies = policies;
        this.statusChanges = new LotStatusChanges(lots, audit, actors, clock);
        this.userNames = userNames;
    }

    @PreAuthorize("hasAuthority('lot.block')")
    @Transactional
    public LotDetail block(long id, String reason) {
        return detail(change(locked(id), Action.BLOCK, AuditAction.LOT_BLOCK, reason));
    }

    @PreAuthorize("hasAuthority('lot.unblock')")
    @Transactional
    public LotDetail unblock(long id, String reason) {
        return detail(change(locked(id), Action.UNBLOCK, AuditAction.LOT_UNBLOCK, reason));
    }

    /** BR-LOT-008: buyers are listed once sales exist (Phase 7, ADR-068). */
    @PreAuthorize("hasAuthority('lot.recall')")
    @Transactional
    public RecallReport recall(long id, String reason) {
        Lot lot = locked(id);
        if (lot.status() != LotStatus.RECALLED) {
            change(lot, Action.RECALL, AuditAction.LOT_RECALL, reason);
        }
        return LotViews.recallReport(lot);
    }

    @PreAuthorize("hasAuthority('lot.recall')")
    @Transactional(readOnly = true)
    public RecallReport recallReport(long id) {
        Lot lot = lots.findById(id).orElseThrow(() -> notFound(id));
        if (lot.status() != LotStatus.RECALLED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "This lot is not recalled.",
                    Map.of("status", lot.status().code()));
        }
        return LotViews.recallReport(lot);
    }

    private Lot change(Lot lot, Action action, AuditAction auditAction, String reason) {
        return statusChanges.change(lot, action, auditAction, reason);
    }

    private Lot locked(long id) {
        return lotLocks.lock(id).orElseThrow(() -> notFound(id));
    }

    private LotDetail detail(Lot lot) {
        return LotViews.detail(lot, products.find(lot.productId()).orElseThrow(), policies.current(),
                LotQueries.blockedByName(lot, userNames));
    }

    private static BusinessException notFound(long id) {
        return new BusinessException(ErrorCode.NOT_FOUND, "Lot not found.", Map.of("id", id));
    }
}
