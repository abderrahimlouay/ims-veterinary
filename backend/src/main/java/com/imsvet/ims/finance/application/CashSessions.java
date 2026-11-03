package com.imsvet.ims.finance.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.idempotency.IdempotencyKeys;
import com.imsvet.ims.common.idempotency.IdempotencyKeys.Claim;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.common.security.Permissions;
import com.imsvet.ims.finance.domain.CashAmounts;
import com.imsvet.ims.finance.domain.CashDirection;
import com.imsvet.ims.finance.domain.CashMovement;
import com.imsvet.ims.finance.domain.CashSession;
import com.imsvet.ims.finance.infrastructure.CashSessionLocks;
import com.imsvet.ims.finance.infrastructure.CashSessionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cash sessions, the minimal slice of Phase 7 (ADR-118, W9): one open session per cashier (BR-CASH-001), closed once
 * with the counted amount; the server computes expected and variance (BR-CASH-002/003). A cashier closes their own
 * session, {@code cash.manage} any (ADR-136: no variance tolerance; the closer is recorded). A non-zero variance is
 * audited in the same transaction. Manual cash movements (Phase 9, ADR-136, BR-CASH-006): {@code cash.manage}, on the
 * user's own open session, with a label; an OUT never beyond the expected cash; audited.
 */
@Service
public class CashSessions {

    private static final String MANAGE = "cash.manage";
    private static final int MONEY_SCALE = 2;

    private static final String MOVEMENT_ENDPOINT = "POST /api/cash-sessions/{id}/movements";
    private static final int LABEL_MAX = 200;

    /** {@code direction}: {@code in} or {@code out}; the method is always cash. */
    public record MovementInput(String direction, BigDecimal amount, String label) {
    }

    private record MovementKey(long sessionId, MovementInput input) {
    }

    private final CashSessionRepository sessions;
    private final CashSessionLocks locks;
    private final CashLedger ledger;
    private final IdempotencyKeys keys;
    private final ActorProvider actors;
    private final AuditRecorder audit;
    private final Clock clock;

    public CashSessions(CashSessionRepository sessions, CashSessionLocks locks, CashLedger ledger, IdempotencyKeys keys,
            ActorProvider actors, AuditRecorder audit, Clock clock) {
        this.sessions = sessions;
        this.locks = locks;
        this.ledger = ledger;
        this.keys = keys;
        this.actors = actors;
        this.audit = audit;
        this.clock = clock;
    }

    @PreAuthorize("hasAuthority('cash.open')")
    @Transactional
    public CashSessionView open(BigDecimal openingAmount) {
        BigDecimal opening = amount("openingAmount", openingAmount);
        long cashier = actors.current().userId();
        if (sessions.findOpenOf(cashier).isPresent()) {
            throw alreadyOpen();
        }
        try {
            return view(sessions.saveAndFlush(new CashSession(cashier, opening, clock.instant())));
        } catch (DataIntegrityViolationException e) {
            throw alreadyOpen();
        }
    }

    /** Empty when the signed-in user has no open session. */
    @PreAuthorize("hasAuthority('cash.open')")
    @Transactional(readOnly = true)
    public Optional<CashSessionView> current() {
        return sessions.findOpenOf(actors.current().userId()).map(this::view);
    }

    @PreAuthorize("hasAnyAuthority('cash.open', 'cash.manage')")
    @Transactional
    public CashSessionView close(long id, BigDecimal countedAmount) {
        BigDecimal counted = amount("countedAmount", countedAmount);
        CashSession session = locks.lock(id).orElseThrow(() -> notFound(id));
        long actor = actors.current().userId();
        if (session.cashierId() != actor && !Permissions.granted(MANAGE)) {
            throw new AccessDeniedException("Closing another user's session needs cash.manage");
        }
        if (!session.open()) {
            throw new BusinessException(ErrorCode.CASH_SESSION_CLOSED, "This cash session is closed.",
                    Map.of("id", id));
        }
        BigDecimal expected = expected(session);
        session.close(expected, counted, actor, clock.instant());
        if (session.varianceAmount().signum() != 0) {
            audit.record(AuditAction.CASH_VARIANCE, "cash_sessions", id,
                    Map.of("expectedAmount", expected.toPlainString(), "cashierId", session.cashierId()),
                    Map.of("countedAmount", counted.toPlainString(),
                            "varianceAmount", session.varianceAmount().toPlainString(), "closedBy", actor));
        }
        return view(sessions.saveAndFlush(session));
    }

    /**
     * BR-CASH-006: a manual movement on the user's own open session ({@code CASH_SESSION_REQUIRED} on someone else's,
     * {@code CASH_SESSION_CLOSED} on a closed one); an OUT beyond the expected cash is refused
     * ({@code CASH_INSUFFICIENT}). Audited {@code CASH_MOVEMENT_CREATE}. Returns the session with its new expected cash.
     */
    @PreAuthorize("hasAuthority('cash.manage')")
    @Transactional
    public CashSessionView addMovement(long id, MovementInput in, String idempotencyKey) {
        List<Violation> violations = new ArrayList<>();
        CashDirection direction = null;
        if (in.direction() == null || in.direction().isBlank()) {
            violations.add(new Violation("direction", "REQUIRED", "In or out is required."));
        } else if (!"in".equals(in.direction()) && !"out".equals(in.direction())) {
            violations.add(new Violation("direction", "UNKNOWN", "In or out."));
        } else {
            direction = CashDirection.fromCode(in.direction());
        }
        if (in.amount() == null) {
            violations.add(new Violation("amount", "REQUIRED", "The amount is required."));
        } else if (in.amount().signum() <= 0) {
            violations.add(new Violation("amount", "POSITIVE", "The amount must be greater than zero."));
        } else if (in.amount().stripTrailingZeros().scale() > MONEY_SCALE) {
            violations.add(new Violation("amount", "SCALE", "At most two decimals."));
        }
        String label = in.label() == null ? "" : in.label().trim();
        if (label.isEmpty()) {
            violations.add(new Violation("label", "REQUIRED", "A manual movement needs its label."));
        } else if (label.length() > LABEL_MAX) {
            violations.add(new Violation("label", "SIZE", "At most " + LABEL_MAX + " characters."));
        }
        if (!violations.isEmpty()) {
            throw new ValidationException("The cash movement is invalid.", violations);
        }
        long actor = actors.current().userId();
        Claim claim = keys.claim(actor, MOVEMENT_ENDPOINT, idempotencyKey, new MovementKey(id, in), "cash_movement");
        if (claim.replayed().isPresent()) {
            return get(id);
        }
        CashSession session = locks.lock(id).orElseThrow(() -> notFound(id));
        if (session.cashierId() != actor) {
            throw new BusinessException(ErrorCode.CASH_SESSION_REQUIRED,
                    "A manual movement goes on your own open session.", Map.of("cashSessionId", id));
        }
        BigDecimal amount = in.amount().setScale(MONEY_SCALE);
        CashMovement movement = ledger.postManual(id, direction, amount, label);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("cashSessionId", id);
        values.put("direction", direction.code());
        values.put("amount", amount.toPlainString());
        values.put("label", label);
        audit.record(AuditAction.CASH_MOVEMENT_CREATE, "cash_movements", movement.id(), Map.of(), values);
        claim.complete(movement.id());
        return view(session);
    }

    @PreAuthorize("hasAuthority('cash.manage')")
    @Transactional(readOnly = true)
    public PageResponse<CashSessionView> list(int page, int size) {
        return PageResponse.of(sessions.findNewestFirst(PageRequests.of(page, size, Sort.unsorted())), this::view);
    }

    @PreAuthorize("hasAuthority('cash.manage')")
    @Transactional(readOnly = true)
    public CashSessionView get(long id) {
        return view(sessions.findById(id).orElseThrow(() -> notFound(id)));
    }

    private CashSessionView view(CashSession session) {
        return CashSessionView.of(session, session.open() ? expected(session) : session.expectedAmount());
    }

    private BigDecimal expected(CashSession session) {
        return CashAmounts.expected(session.openingAmount(), sessions.cashTotal(session.id(), CashDirection.IN.code()),
                sessions.cashTotal(session.id(), CashDirection.OUT.code())).setScale(MONEY_SCALE);
    }

    private static BigDecimal amount(String field, BigDecimal value) {
        if (value == null) {
            throw invalid(field, "REQUIRED", "The amount is required.");
        }
        if (value.signum() < 0) {
            throw invalid(field, "RANGE", "The amount cannot be negative.");
        }
        if (value.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw invalid(field, "SCALE", "At most two decimals.");
        }
        return value.setScale(MONEY_SCALE);
    }

    private static BusinessException alreadyOpen() {
        return new BusinessException(ErrorCode.CASH_SESSION_ALREADY_OPEN, "You already have an open cash session.",
                Map.of());
    }

    private static BusinessException notFound(long id) {
        return new BusinessException(ErrorCode.NOT_FOUND, "Cash session not found.", Map.of("id", id));
    }

    private static ValidationException invalid(String field, String code, String message) {
        return new ValidationException("The cash session is invalid.", List.of(new Violation(field, code, message)));
    }
}
