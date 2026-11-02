package com.imsvet.ims.finance.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.finance.domain.CustomerRefund;
import com.imsvet.ims.finance.domain.PaymentMethod;
import com.imsvet.ims.finance.infrastructure.CustomerRefundRepository;
import com.imsvet.ims.sales.application.PaymentRecorder.Tender;
import com.imsvet.ims.sales.application.RefundRecorder;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The refunds of a customer return on an immediate sale (ADR-128, BR-RET-006, BR-RET-011): one append-only row per
 * tender, valid per BR-PAY-001, together exactly the credit note's TTC. A cash part locks the refunder's own open
 * session first (BR-PAY-006, lock order of ADR-130) and goes out of it, refused if the session's expected cash would
 * fall below zero. Each refund is audited {@code REFUND_CREATE}. The caller checks its permission.
 */
@Service
@InternalService
public class CounterRefunds implements RefundRecorder {

    private static final String REFUND_INVALID = "The refund is invalid.";

    private final CustomerRefundRepository refunds;
    private final CashLedger ledger;
    private final ActorProvider actors;
    private final AuditRecorder audit;
    private final Clock clock;

    public CounterRefunds(CustomerRefundRepository refunds, CashLedger ledger, ActorProvider actors,
            AuditRecorder audit, Clock clock) {
        this.refunds = refunds;
        this.ledger = ledger;
        this.actors = actors;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public List<RecordedRefund> refund(RefundRequest request) {
        List<Tender> tenders = request.tenders() == null ? List.of() : request.tenders();
        List<PaymentMethod> methods = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < tenders.size(); i++) {
            methods.add(Tenders.validate(tenders.get(i), "refund[" + i + "].", REFUND_INVALID));
            total = total.add(tenders.get(i).amount());
        }
        if (total.compareTo(request.creditNoteTtc()) != 0) {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("expected", request.creditNoteTtc().setScale(Tenders.MONEY_SCALE).toPlainString());
            context.put("given", total.setScale(Tenders.MONEY_SCALE).toPlainString());
            throw new BusinessException(ErrorCode.REFUND_TOTAL_MISMATCH,
                    "The refund must equal the credit note's total.", context);
        }
        Long session = methods.contains(PaymentMethod.CASH) ? ledger.lockCurrentSession() : null;
        if (session != null) {
            BigDecimal cash = BigDecimal.ZERO;
            for (int i = 0; i < tenders.size(); i++) {
                if (methods.get(i) == PaymentMethod.CASH) {
                    cash = cash.add(tenders.get(i).amount());
                }
            }
            ledger.requireAvailable(session, cash);
        }
        long actor = actors.current().userId();
        List<RecordedRefund> recorded = new ArrayList<>();
        for (int i = 0; i < tenders.size(); i++) {
            Tender tender = tenders.get(i);
            boolean cash = methods.get(i) == PaymentMethod.CASH;
            CustomerRefund refund = refunds.saveAndFlush(new CustomerRefund(request.creditNoteId(),
                    request.customerId(), methods.get(i), tender.amount().setScale(Tenders.MONEY_SCALE),
                    Tenders.reference(tender), cash ? session : null, actor, clock.instant()));
            if (cash) {
                ledger.postRefund(session, refund.amount(), "refund of credit note " + request.creditNoteId(),
                        refund.id());
            }
            audit(request, refund);
            recorded.add(view(refund));
        }
        return recorded;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecordedRefund> refundsOf(long creditNoteId) {
        return refunds.findByCreditNoteIdOrderByIdAsc(creditNoteId).stream().map(CounterRefunds::view).toList();
    }

    private void audit(RefundRequest request, CustomerRefund refund) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("creditNoteId", request.creditNoteId());
        values.put("documentId", request.originalDocumentId());
        values.put("method", refund.method().code());
        values.put("amount", refund.amount().toPlainString());
        values.put("originalMethods", request.originalMethods() == null ? List.of() : request.originalMethods());
        if (refund.cashSessionId() != null) {
            values.put("cashSessionId", refund.cashSessionId());
        }
        audit.record(AuditAction.REFUND_CREATE, "customer_refunds", refund.id(), null, values);
    }

    private static RecordedRefund view(CustomerRefund refund) {
        return new RecordedRefund(refund.id(), refund.method().code(), refund.amount(), refund.reference(),
                refund.cashSessionId());
    }
}
