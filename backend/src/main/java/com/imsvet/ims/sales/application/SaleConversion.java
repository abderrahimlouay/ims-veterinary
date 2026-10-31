package com.imsvet.ims.sales.application;

import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.sales.application.SalesViews.SaleDetail;
import com.imsvet.ims.sales.domain.Customer;
import com.imsvet.ims.sales.domain.SalesDocStatus;
import com.imsvet.ims.sales.domain.SalesDocType;
import com.imsvet.ims.sales.domain.SalesDocument;
import com.imsvet.ims.sales.domain.SalesDocumentLine;
import com.imsvet.ims.sales.infrastructure.CustomerRepository;
import com.imsvet.ims.sales.infrastructure.SalesDocumentLineRepository;
import com.imsvet.ims.sales.infrastructure.SalesDocumentLocks;
import com.imsvet.ims.sales.infrastructure.SalesDocumentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derives a new draft from a document (W11, BR-SALE-001): quote → delivery note or invoice, while draft or confirmed,
 * its lines taking their lots by FEFO at the quoted price; a confirmed delivery note → exactly one invoice, under the
 * note's row lock, its lines, lots, prices and costs frozen (ADR-125). The source never changes.
 */
@Service
public class SaleConversion {

    private final SalesDocumentRepository documents;
    private final SalesDocumentLineRepository lines;
    private final SalesDocumentLocks locks;
    private final CustomerRepository customers;
    private final SaleLines saleLines;
    private final SaleViewer viewer;
    private final ActorProvider actors;
    private final Clock clock;

    public SaleConversion(SalesDocumentRepository documents, SalesDocumentLineRepository lines,
            SalesDocumentLocks locks, CustomerRepository customers, SaleLines saleLines, SaleViewer viewer,
            ActorProvider actors, Clock clock) {
        this.documents = documents;
        this.lines = lines;
        this.locks = locks;
        this.customers = customers;
        this.saleLines = saleLines;
        this.viewer = viewer;
        this.actors = actors;
        this.clock = clock;
    }

    @PreAuthorize("hasAuthority('sale.create')")
    @Transactional
    public SaleDetail convert(long id, String targetType) {
        SalesDocType target = target(targetType);
        SalesDocument source = documents.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "Sales document not found.", Map.of("id", id)));
        locks.lockAndRefresh(source);
        requireConvertible(source, target);
        Instant now = clock.instant();
        SalesDocument derived = documents.saveAndFlush(
                SalesDocument.derivedFrom(source, target, actors.current().userId(), now));
        List<SalesDocumentLine> found = lines.findByDocumentIdOrderByIdAsc(id);
        if (source.docType() == SalesDocType.DELIVERY_NOTE) {
            found.forEach(line -> lines.saveAndFlush(line.frozenCopyFor(derived.id())));
        } else {
            found.forEach(line -> saleLines.copyWithLots(derived, line));
        }
        SaleTotals.apply(derived, lines.findByDocumentIdOrderByIdAsc(derived.id()), now);
        return viewer.detail(documents.saveAndFlush(derived));
    }

    private void requireConvertible(SalesDocument source, SalesDocType target) {
        if (!target.creatableDirectly() || !target.canDeriveFrom(source.docType())) {
            throw refused("This document cannot be converted to that type.", source);
        }
        boolean note = source.docType() == SalesDocType.DELIVERY_NOTE;
        if (source.status() == SalesDocStatus.CANCELLED || (note && source.status() != SalesDocStatus.CONFIRMED)) {
            throw refused("This document cannot be converted in its status.", source);
        }
        if (note && documents.existsByParentIdAndStatusNot(source.id(), SalesDocStatus.CANCELLED)) {
            throw refused("This delivery note already has an invoice.", source);
        }
        Customer customer = customers.findById(source.customerId()).orElseThrow();
        if (!customer.active()) {
            throw new BusinessException(ErrorCode.CUSTOMER_INACTIVE, "This customer is inactive.",
                    Map.of("customerId", customer.id()));
        }
    }

    private static BusinessException refused(String message, SalesDocument source) {
        return new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, message,
                Map.of("docType", source.docType().code(), "status", source.status().code()));
    }

    private static SalesDocType target(String code) {
        if (code == null || code.isBlank()) {
            throw invalid("REQUIRED", "The target type is required.");
        }
        return SalesDocType.fromCode(code).orElseThrow(() -> invalid("UNKNOWN", "Unknown document type."));
    }

    private static ValidationException invalid(String code, String message) {
        return new ValidationException("The conversion is invalid.", List.of(new Violation("targetType", code, message)));
    }
}
