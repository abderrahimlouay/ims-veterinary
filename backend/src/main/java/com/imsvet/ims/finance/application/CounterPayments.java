package com.imsvet.ims.finance.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.finance.domain.CashDirection;
import com.imsvet.ims.finance.domain.CustomerPayment;
import com.imsvet.ims.finance.domain.PaymentAllocation;
import com.imsvet.ims.finance.domain.PaymentMethod;
import com.imsvet.ims.finance.infrastructure.CustomerPaymentRepository;
import com.imsvet.ims.finance.infrastructure.PaymentAllocationRepository;
import com.imsvet.ims.sales.application.PaymentRecorder;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payments of a sale at the counter (ADR-118): one payment and allocation per tender, valid per BR-PAY-001 and at
 * most the document's total (BR-PAY-002). A cash part locks the user's open session and posts what is kept
 * (BR-PAY-006, BR-CASH-003). A reversal is a new row, audited, with the cash going out of the same session
 * (BR-PAY-003); a closed session refuses it (BR-CASH-004). The caller checks its permission.
 */
@Service
@InternalService
public class CounterPayments implements PaymentRecorder {

    private static final int MONEY_SCALE = 2;
    private static final int REFERENCE_MAX = 80;

    private final CustomerPaymentRepository payments;
    private final PaymentAllocationRepository allocations;
    private final CashLedger ledger;
    private final ActorProvider actors;
    private final AuditRecorder audit;
    private final Clock clock;

    public CounterPayments(CustomerPaymentRepository payments, PaymentAllocationRepository allocations,
            CashLedger ledger, ActorProvider actors, AuditRecorder audit, Clock clock) {
        this.payments = payments;
        this.allocations = allocations;
        this.ledger = ledger;
        this.actors = actors;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public RecordedPayments record(long documentId, Long customerId, BigDecimal documentTtc, List<Tender> tenders) {
        List<PaymentMethod> methods = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < tenders.size(); i++) {
            methods.add(validate(tenders.get(i), "payments[" + i + "]."));
            total = total.add(tenders.get(i).amount());
        }
        if (total.compareTo(documentTtc) > 0) {
            throw new BusinessException(ErrorCode.PAYMENT_ALLOCATION_EXCEEDED, "The payments exceed the document's total.",
                    Map.of("documentId", documentId, "total", documentTtc.toPlainString()));
        }
        Long session = methods.contains(PaymentMethod.CASH) ? ledger.lockCurrentSession() : null;
        long actor = actors.current().userId();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < tenders.size(); i++) {
            Tender tender = tenders.get(i);
            boolean cash = methods.get(i) == PaymentMethod.CASH;
            CustomerPayment payment = payments.saveAndFlush(new CustomerPayment(customerId, methods.get(i),
                    tender.amount().setScale(MONEY_SCALE), reference(tender), cash ? session : null, actor,
                    clock.instant()));
            allocations.saveAndFlush(new PaymentAllocation(payment.id(), documentId, payment.amount()));
            if (cash) {
                ledger.post(session, CashDirection.IN, PaymentMethod.CASH, payment.amount(), "sale " + documentId,
                        payment.id());
            }
            ids.add(payment.id());
        }
        return new RecordedPayments(session, ids);
    }

    @Override
    @Transactional
    public List<Long> reverseAll(long documentId, String reason) {
        long actor = actors.current().userId();
        List<Long> reversals = new ArrayList<>();
        for (CustomerPayment payment : payments.inForceOn(documentId)) {
            CustomerPayment reversal = payments.saveAndFlush(payment.reversal(reason, actor, clock.instant()));
            if (payment.method() == PaymentMethod.CASH) {
                ledger.post(payment.cashSessionId(), CashDirection.OUT, PaymentMethod.CASH, payment.amount(),
                        "reversal of sale " + documentId, reversal.id());
            }
            audit.record(AuditAction.PAYMENT_REVERSE, "customer_payments", payment.id(),
                    Map.of("amount", payment.amount().toPlainString(), "documentId", documentId),
                    Map.of("reversalId", reversal.id(), "reason", reason));
            reversals.add(reversal.id());
        }
        return reversals;
    }

    private static PaymentMethod validate(Tender tender, String prefix) {
        PaymentMethod method = tender.method() == null ? null : PaymentMethod.fromCode(tender.method())
                .orElseThrow(() -> invalid(prefix + "method", "UNKNOWN", "Unknown payment method."));
        if (method == null) {
            throw invalid(prefix + "method", "REQUIRED", "The payment method is required.");
        }
        BigDecimal amount = tender.amount();
        if (amount == null) {
            throw invalid(prefix + "amount", "REQUIRED", "The amount is required.");
        }
        if (amount.signum() <= 0) {
            throw invalid(prefix + "amount", "POSITIVE", "The amount must be greater than zero.");
        }
        if (amount.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw invalid(prefix + "amount", "SCALE", "At most two decimals.");
        }
        String reference = reference(tender);
        if (method != PaymentMethod.CASH && reference == null) {
            throw invalid(prefix + "reference", "REQUIRED", "A cheque or a transfer needs its reference.");
        }
        if (reference != null && reference.length() > REFERENCE_MAX) {
            throw invalid(prefix + "reference", "SIZE", "At most " + REFERENCE_MAX + " characters.");
        }
        return method;
    }

    private static String reference(Tender tender) {
        return tender.reference() == null || tender.reference().isBlank() ? null : tender.reference().trim();
    }

    private static ValidationException invalid(String field, String code, String message) {
        return new ValidationException("The payment is invalid.", List.of(new Violation(field, code, message)));
    }
}
