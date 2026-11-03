package com.imsvet.ims.finance.application;

import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.finance.domain.CashAmounts;
import com.imsvet.ims.finance.domain.CashDirection;
import com.imsvet.ims.finance.domain.CashMovement;
import com.imsvet.ims.finance.domain.CashSession;
import com.imsvet.ims.finance.domain.PaymentMethod;
import com.imsvet.ims.finance.infrastructure.CashMovementRepository;
import com.imsvet.ims.finance.infrastructure.CashSessionLocks;
import com.imsvet.ims.finance.infrastructure.CashSessionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Port for the payments: posts to a session under its row lock, so nothing is posted to a closed one (BR-CASH-004).
 * The amount is what was kept, never what was tendered (ADR-118). The caller checks its own permission and joins its
 * transaction with this one.
 */
@Service
@InternalService
public class CashLedger {

    private final CashSessionRepository sessions;
    private final CashSessionLocks locks;
    private final CashMovementRepository movements;
    private final ActorProvider actors;
    private final Clock clock;

    public CashLedger(CashSessionRepository sessions, CashSessionLocks locks, CashMovementRepository movements,
            ActorProvider actors, Clock clock) {
        this.sessions = sessions;
        this.locks = locks;
        this.movements = movements;
        this.actors = actors;
        this.clock = clock;
    }

    /** The signed-in user's open session, locked; {@code CASH_SESSION_REQUIRED} without one (BR-PAY-006). */
    @Transactional
    public long lockCurrentSession() {
        long cashier = actors.current().userId();
        CashSession open = sessions.findOpenOf(cashier).orElseThrow(ledgerRefusal(cashier));
        CashSession locked = locks.lock(open.id()).orElseThrow();
        if (!locked.open()) {
            throw ledgerRefusal(cashier).get();
        }
        return locked.id();
    }

    @Transactional
    public CashMovement post(long sessionId, CashDirection direction, PaymentMethod method, BigDecimal amount,
            String label, Long customerPaymentId) {
        CashSession session = locks.lock(sessionId).orElseThrow();
        if (!session.open()) {
            throw new BusinessException(ErrorCode.CASH_SESSION_CLOSED, "This cash session is closed.",
                    Map.of("id", sessionId));
        }
        return movements.saveAndFlush(new CashMovement(sessionId, direction, method, amount, label, customerPaymentId,
                actors.current().userId(), clock.instant()));
    }

    /**
     * BR-RET-011, ADR-135: refuses with {@code CASH_INSUFFICIENT} if paying {@code amount} out of the session would
     * bring its expected cash (BR-CASH-003) below zero. Call it under the session's lock, before writing anything.
     */
    @Transactional(readOnly = true)
    public void requireAvailable(long sessionId, BigDecimal amount) {
        CashSession session = sessions.findById(sessionId).orElseThrow();
        BigDecimal expected = CashAmounts.expected(session.openingAmount(),
                sessions.cashTotal(sessionId, CashDirection.IN.code()),
                sessions.cashTotal(sessionId, CashDirection.OUT.code()));
        if (expected.compareTo(amount) < 0) {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("cashSessionId", sessionId);
            context.put("expected", expected.setScale(2).toPlainString());
            context.put("requested", amount.setScale(2).toPlainString());
            throw new BusinessException(ErrorCode.CASH_INSUFFICIENT, "Not enough cash in the session for this payment out.",
                    context);
        }
    }

    /**
     * Pays a refund out of the session in cash (ADR-128), under the session's row lock: refused on a closed session
     * (BR-CASH-004) and when the expected cash would fall below zero ({@link #requireAvailable}).
     */
    @Transactional
    public CashMovement postRefund(long sessionId, BigDecimal amount, String label, long customerRefundId) {
        CashSession session = locks.lock(sessionId).orElseThrow();
        if (!session.open()) {
            throw new BusinessException(ErrorCode.CASH_SESSION_CLOSED, "This cash session is closed.",
                    Map.of("id", sessionId));
        }
        requireAvailable(sessionId, amount);
        return movements.saveAndFlush(CashMovement.refund(sessionId, amount, label, customerRefundId,
                actors.current().userId(), clock.instant()));
    }

    /**
     * The cash of a supplier payment (OUT, refused beyond the expected cash) or of its reversal (IN), under the
     * session's row lock; refused on a closed session (ADR-132, ADR-135, BR-CASH-004).
     */
    @Transactional
    public CashMovement postSupplierPayment(long sessionId, CashDirection direction, BigDecimal amount, String label,
            long supplierPaymentId) {
        CashSession session = locks.lock(sessionId).orElseThrow();
        if (!session.open()) {
            throw new BusinessException(ErrorCode.CASH_SESSION_CLOSED, "This cash session is closed.",
                    Map.of("id", sessionId));
        }
        if (direction == CashDirection.OUT) {
            requireAvailable(sessionId, amount);
        }
        return movements.saveAndFlush(CashMovement.supplierPayment(sessionId, direction, amount, label,
                supplierPaymentId, actors.current().userId(), clock.instant()));
    }

    private static Supplier<BusinessException> ledgerRefusal(long cashier) {
        return () -> new BusinessException(ErrorCode.CASH_SESSION_REQUIRED, "A cash payment needs an open cash session.",
                Map.of("cashierId", cashier));
    }
}
