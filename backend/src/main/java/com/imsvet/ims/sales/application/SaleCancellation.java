package com.imsvet.ims.sales.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.inventory.application.StockService;
import com.imsvet.ims.inventory.application.StockService.Movement;
import com.imsvet.ims.inventory.domain.MovementType;
import com.imsvet.ims.sales.application.SalesViews.SaleDetail;
import com.imsvet.ims.sales.domain.SalesDocStatus;
import com.imsvet.ims.sales.domain.SalesDocType;
import com.imsvet.ims.sales.domain.SalesDocument;
import com.imsvet.ims.sales.infrastructure.SalesDocumentLineRepository;
import com.imsvet.ims.sales.infrastructure.SalesDocumentLocks;
import com.imsvet.ims.sales.infrastructure.SalesDocumentRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancels a whole confirmed document (W2b, BR-SALE-014, ADR-119), one transaction under the document's row lock, so a
 * second cancellation waits and is refused: refused with a non-cancelled derived document or a closed session; audited
 * {@code INVOICE_CANCEL}; one {@code sale_cancellation} per line that posted stock, back to its lot whatever its status;
 * the payments reversed, cash out of the same session; the number kept. The invoice of a delivery note posted nothing, so
 * it returns nothing, and the note can be invoiced again (ADR-125). Returns join the refusal with their table in Phase 8.
 */
@Service
public class SaleCancellation {

    private static final int REASON_MAX = 500;
    private static final String REFERENCE_TYPE = "sales_document";

    private final SalesDocumentRepository documents;
    private final SalesDocumentLineRepository lines;
    private final SalesDocumentLocks locks;
    private final StockService stock;
    private final PaymentRecorder payments;
    private final AuditRecorder audit;
    private final SaleViewer viewer;
    private final ActorProvider actors;
    private final Clock clock;

    public SaleCancellation(SalesDocumentRepository documents, SalesDocumentLineRepository lines,
            SalesDocumentLocks locks, StockService stock, PaymentRecorder payments, AuditRecorder audit,
            SaleViewer viewer, ActorProvider actors, Clock clock) {
        this.documents = documents;
        this.lines = lines;
        this.locks = locks;
        this.stock = stock;
        this.payments = payments;
        this.audit = audit;
        this.viewer = viewer;
        this.actors = actors;
        this.clock = clock;
    }

    @PreAuthorize("hasAuthority('sale.cancel')")
    @Transactional
    public SaleDetail cancel(long id, String reason) {
        String why = reason(reason);
        SalesDocument document = documents.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "Sales document not found.", Map.of("id", id)));
        locks.lockAndRefresh(document);
        requireCancellable(document);
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("status", SalesDocStatus.CANCELLED.code());
        newValues.put("reason", why);
        newValues.put("number", document.number());
        audit.record(AuditAction.INVOICE_CANCEL, "sales_documents", id, Map.of("status", document.status().code()),
                newValues);
        if (document.docType() != SalesDocType.QUOTE && !documents.invoicesADeliveryNote(document)) {
            stock.post(lines.findByDocumentIdOrderByIdAsc(id).stream().map(line -> new Movement(line.lotId(),
                    line.productId(), MovementType.SALE_CANCELLATION, line.quantityBase(), null, REFERENCE_TYPE, id))
                    .toList());
        }
        payments.reverseAll(id, why);
        document.cancel(why, actors.current().userId(), clock.instant());
        return viewer.detail(documents.saveAndFlush(document));
    }

    private void requireCancellable(SalesDocument document) {
        if (document.status() != SalesDocStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "Only a confirmed document can be cancelled.",
                    Map.of("status", document.status().code(), "action", "cancel"));
        }
        if (documents.existsByParentIdAndStatusNot(document.id(), SalesDocStatus.CANCELLED)) {
            throw new BusinessException(ErrorCode.SALE_NOT_CANCELLABLE, "A later document exists; use a credit note.",
                    Map.of("blockedBy", "derived_document", "documentId", document.id()));
        }
        Long session = document.cashSessionId();
        if (session != null && !payments.sessionOpen(session)) {
            throw new BusinessException(ErrorCode.SALE_NOT_CANCELLABLE, "Its cash session is closed; use a credit note.",
                    Map.of("blockedBy", "cash_session", "cashSessionId", session));
        }
    }

    private static String reason(String reason) {
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw invalid("REQUIRED", "A reason is required.");
        }
        if (why.length() > REASON_MAX) {
            throw invalid("SIZE", "At most " + REASON_MAX + " characters.");
        }
        return why;
    }

    private static ValidationException invalid(String code, String message) {
        return new ValidationException("The cancellation is invalid.", List.of(new Violation("reason", code, message)));
    }
}
