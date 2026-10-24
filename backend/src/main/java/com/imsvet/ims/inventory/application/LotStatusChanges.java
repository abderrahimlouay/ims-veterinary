package com.imsvet.ims.inventory.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.inventory.domain.Lot;
import com.imsvet.ims.inventory.domain.LotStatus;
import com.imsvet.ims.inventory.domain.LotStatus.Action;
import com.imsvet.ims.inventory.domain.LotStatus.IllegalLotTransitionException;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One stored-status change of a locked lot (BR-LOT-009, ADR-060): refused outside the table
 * ({@code INVALID_STATE_TRANSITION}), audited in the same transaction before the lot changes (BR-AUDIT-003), then
 * reason, user and time recorded. Shared by the lot actions and the receipt's blocking of a non-conforming lot.
 */
final class LotStatusChanges {

    private final LotRepository lots;
    private final AuditRecorder audit;
    private final ActorProvider actors;
    private final Clock clock;

    LotStatusChanges(LotRepository lots, AuditRecorder audit, ActorProvider actors, Clock clock) {
        this.lots = lots;
        this.audit = audit;
        this.actors = actors;
        this.clock = clock;
    }

    Lot change(Lot lot, Action action, AuditAction auditAction, String reason) {
        return change(lot, action, auditAction, reason, clock.instant());
    }

    /** {@code at}: the time recorded on the lot, e.g. the receipt's that blocks it (ADR-106 compares it). */
    Lot change(Lot lot, Action action, AuditAction auditAction, String reason, Instant at) {
        LotStatus next;
        try {
            next = lot.status().after(action);
        } catch (IllegalLotTransitionException e) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, e.getMessage(),
                    Map.of("status", lot.status().code(), "action", action.name()));
        }
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("status", next.code());
        newValues.put("reason", reason);
        audit.record(auditAction, "lots", lot.id(), Map.of("status", lot.status().code()), newValues);
        lot.apply(action, reason, actors.current().userId(), at);
        lots.flush();
        return lot;
    }
}
