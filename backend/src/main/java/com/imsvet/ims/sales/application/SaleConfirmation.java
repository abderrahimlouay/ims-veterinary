package com.imsvet.ims.sales.application;

import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.idempotency.IdempotencyKeys;
import com.imsvet.ims.common.idempotency.IdempotencyKeys.Claim;
import com.imsvet.ims.inventory.application.SaleLots;
import com.imsvet.ims.inventory.application.StockService;
import com.imsvet.ims.inventory.application.StockService.Movement;
import com.imsvet.ims.inventory.domain.MovementType;
import com.imsvet.ims.numbering.application.NumberingService;
import com.imsvet.ims.sales.application.PaymentRecorder.RecordedPayments;
import com.imsvet.ims.sales.application.PaymentRecorder.Tender;
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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Confirms an immediate sale, a receipt or a direct invoice (W2 step 6, api.md §5.6), all or nothing, in the lock order
 * of database.md §7: idempotency key (a repeat returns the first result, BR-SALE-016), document, number (ADR-122), lots
 * in ascending id, validated again under lock (BR-LOT-003/004), {@code sale} movements through {@link StockService}
 * (BR-SALE-015), then the payments and the cash session (ADR-118). Paid in full: the amounts are what is kept, never
 * what was tendered. A credit sale is refused until {@code CreditLimitPort} exists, so none is ever confirmed without
 * the limit check; quotes and delivery notes are confirmed with the conversions.
 */
@Service
public class SaleConfirmation {

    /** What the idempotency key is compared with. */
    record ConfirmRequest(long documentId, List<Tender> payments) {
    }

    private static final String ENDPOINT = "POST /api/sales/{id}/confirm";
    private static final String REFERENCE_TYPE = "sales_document";

    private final SalesDocumentRepository documents;
    private final SalesDocumentLineRepository lines;
    private final SalesDocumentLocks locks;
    private final CustomerRepository customers;
    private final ProductLookup products;
    private final SaleLots lots;
    private final StockService stock;
    private final NumberingService numbering;
    private final PaymentRecorder payments;
    private final IdempotencyKeys keys;
    private final SaleViewer viewer;
    private final ActorProvider actors;
    private final Clock clock;

    public SaleConfirmation(SalesDocumentRepository documents, SalesDocumentLineRepository lines,
            SalesDocumentLocks locks, CustomerRepository customers, ProductLookup products, SaleLots lots,
            StockService stock, NumberingService numbering, PaymentRecorder payments, IdempotencyKeys keys,
            SaleViewer viewer, ActorProvider actors, Clock clock) {
        this.documents = documents;
        this.lines = lines;
        this.locks = locks;
        this.customers = customers;
        this.products = products;
        this.lots = lots;
        this.stock = stock;
        this.numbering = numbering;
        this.payments = payments;
        this.keys = keys;
        this.viewer = viewer;
        this.actors = actors;
        this.clock = clock;
    }

    /** {@code idempotencyKey}: the {@code Idempotency-Key} header, {@code null} when absent. */
    @PreAuthorize("hasAuthority('sale.create')")
    @Transactional
    public SaleDetail confirm(long id, List<Tender> tenders, String idempotencyKey) {
        List<Tender> paid = tenders == null ? List.of() : tenders;
        Claim claim = keys.claim(actors.current().userId(), ENDPOINT, idempotencyKey, new ConfirmRequest(id, paid),
                "sales_document");
        if (claim.replayed().isPresent()) {
            return viewer.detail(find(claim.replayed().get()));
        }
        SalesDocument document = find(id);
        locks.lockAndRefresh(document);
        requireConfirmable(document);
        List<SalesDocumentLine> found = lines.findByDocumentIdOrderByIdAsc(id);
        requireLines(document, found);
        Instant now = clock.instant();
        SaleTotals.apply(document, found, now);
        requirePaidInFull(document, paid);

        String number = numbering.next(document.docType().key());
        Map<Long, BigDecimal> costs = lots.lockForSale(found.stream().map(SalesDocumentLine::lotId).toList());
        found.forEach(line -> line.snapshotCost(costs.get(line.lotId())));
        stock.post(found.stream().map(line -> new Movement(line.lotId(), line.productId(), MovementType.SALE,
                line.quantityBase().negate(), null, REFERENCE_TYPE, id)).toList());
        RecordedPayments recorded = payments.record(id, document.customerId(), document.totalTtc(), paid);
        document.confirm(number, recorded.cashSessionId(), now);
        documents.saveAndFlush(document);
        found.forEach(lines::saveAndFlush);
        claim.complete(id);
        return viewer.detail(document);
    }

    private void requireConfirmable(SalesDocument document) {
        if (document.status() != SalesDocStatus.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "Only a draft can be confirmed.",
                    Map.of("status", document.status().code()));
        }
        if (document.creditSale()) {
            throw new BusinessException(ErrorCode.CREDIT_SALE_UNAVAILABLE,
                    "Credit sales cannot be confirmed until the credit-limit check is available.",
                    Map.of("documentId", document.id()));
        }
        SalesDocType type = document.docType();
        if (type != SalesDocType.RECEIPT && type != SalesDocType.INVOICE) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This document type cannot be confirmed yet.", Map.of("docType", type.code()));
        }
        if (document.customerId() != null) {
            Customer customer = customers.findById(document.customerId()).orElseThrow();
            if (!customer.active()) {
                throw new BusinessException(ErrorCode.CUSTOMER_INACTIVE, "This customer is inactive.",
                        Map.of("customerId", customer.id()));
            }
        }
    }

    private void requireLines(SalesDocument document, List<SalesDocumentLine> found) {
        if (found.isEmpty()) {
            throw new ValidationException("The sales document is invalid.",
                    List.of(new Violation("lines", "REQUIRED", "A document needs at least one line.")));
        }
        for (SalesDocumentLine line : found) {
            if (line.lotId() == null) {
                throw new BusinessException(ErrorCode.LOT_REQUIRED, "Every line needs a lot.",
                        Map.of("documentId", document.id(), "lineId", line.id()));
            }
            ProductRef product = products.find(line.productId()).orElseThrow();
            if (product.archived()) {
                throw new BusinessException(ErrorCode.PRODUCT_ARCHIVED, "This product is archived.",
                        Map.of("productId", line.productId()));
            }
            if (line.unitId() != null && !products.unit(line.productId(), line.unitId()).orElseThrow().sellable()) {
                throw new BusinessException(ErrorCode.UNIT_NOT_SELLABLE, "This unit cannot be sold.",
                        Map.of("unitId", line.unitId()));
            }
        }
    }

    private static void requirePaidInFull(SalesDocument document, List<Tender> paid) {
        List<Violation> missing = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < paid.size(); i++) {
            if (paid.get(i).amount() == null) {
                missing.add(new Violation("payments[" + i + "].amount", "REQUIRED", "The amount is required."));
            } else {
                total = total.add(paid.get(i).amount());
            }
        }
        if (!missing.isEmpty()) {
            throw new ValidationException("The payment is invalid.", missing);
        }
        if (total.compareTo(document.totalTtc()) != 0) {
            throw new BusinessException(ErrorCode.PAYMENT_TOTAL_MISMATCH,
                    "An immediate sale is paid in full: the payments must equal its total.",
                    Map.of("total", document.totalTtc().toPlainString(), "paid", total.toPlainString()));
        }
    }

    private SalesDocument find(long id) {
        return documents.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "Sales document not found.", Map.of("id", id)));
    }
}
