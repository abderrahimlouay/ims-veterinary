package com.imsvet.ims.finance.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.common.security.Permissions;
import com.imsvet.ims.finance.domain.CashAmounts;
import com.imsvet.ims.finance.domain.CashDirection;
import com.imsvet.ims.finance.domain.CashSession;
import com.imsvet.ims.finance.infrastructure.CashSessionLocks;
import com.imsvet.ims.finance.infrastructure.CashSessionRepository;
import java.math.BigDecimal;
import java.time.Clock;
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
 * session, {@code cash.manage} any. A non-zero variance is audited in the same transaction. Manual cash movements and
 * the session report come in Phase 9.
 */
@Service
public class CashSessions {

    private static final String MANAGE = "cash.manage";
    private static final int MONEY_SCALE = 2;

    private final CashSessionRepository sessions;
    private final CashSessionLocks locks;
    private final ActorProvider actors;
    private final AuditRecorder audit;
    private final Clock clock;

    public CashSessions(CashSessionRepository sessions, CashSessionLocks locks, ActorProvider actors,
            AuditRecorder audit, Clock clock) {
        this.sessions = sessions;
        this.locks = locks;
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
                    Map.of("expectedAmount", expected.toPlainString()),
                    Map.of("countedAmount", counted.toPlainString(),
                            "varianceAmount", session.varianceAmount().toPlainString()));
        }
        return view(sessions.saveAndFlush(session));
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
