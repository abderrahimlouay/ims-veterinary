package com.imsvet.ims.sales.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.idempotency.IdempotencyKeys;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.common.idempotency.IdempotencyKeys.Claim;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.common.time.BusinessSettings;
import com.imsvet.ims.inventory.application.ReturnLots;
import com.imsvet.ims.inventory.application.ReturnLots.ReturnLot;
import com.imsvet.ims.inventory.application.StockService;
import com.imsvet.ims.inventory.application.StockService.Movement;
import com.imsvet.ims.inventory.domain.MovementType;
import com.imsvet.ims.numbering.application.NumberingService;
import com.imsvet.ims.numbering.domain.DocumentKey;
import com.imsvet.ims.sales.application.PaymentRecorder.Tender;
import com.imsvet.ims.sales.application.RefundRecorder.RecordedRefund;
import com.imsvet.ims.sales.application.RefundRecorder.RefundRequest;
import com.imsvet.ims.sales.application.ReturnViews.CreditLine;
import com.imsvet.ims.sales.application.ReturnViews.CreditNoteRef;
import com.imsvet.ims.sales.application.ReturnViews.CreditPreview;
import com.imsvet.ims.sales.application.ReturnViews.CustomerReturnDetail;
import com.imsvet.ims.sales.application.ReturnViews.CustomerReturnSummary;
import com.imsvet.ims.sales.application.ReturnViews.DocumentRef;
import com.imsvet.ims.sales.application.ReturnViews.LotItem;
import com.imsvet.ims.sales.application.ReturnViews.OriginalDocument;
import com.imsvet.ims.sales.application.ReturnViews.PreviewLine;
import com.imsvet.ims.sales.application.ReturnViews.ProductItem;
import com.imsvet.ims.sales.application.ReturnViews.RefundItem;
import com.imsvet.ims.sales.application.ReturnViews.ReturnPreview;
import com.imsvet.ims.sales.application.ReturnViews.ReturnedLine;
import com.imsvet.ims.sales.application.SalesViews.CustomerRef;
import com.imsvet.ims.sales.domain.CustomerReturn;
import com.imsvet.ims.sales.domain.CustomerReturnLine;
import com.imsvet.ims.sales.domain.DiscardReason;
import com.imsvet.ims.sales.domain.RestockRefusal;
import com.imsvet.ims.sales.domain.ReturnCredit;
import com.imsvet.ims.sales.domain.ReturnCredit.Credit;
import com.imsvet.ims.sales.domain.ReturnCredit.SoldLine;
import com.imsvet.ims.sales.domain.ReturnDisposition;
import com.imsvet.ims.sales.domain.SalesDocStatus;
import com.imsvet.ims.sales.domain.SalesDocType;
import com.imsvet.ims.sales.domain.SalesDocument;
import com.imsvet.ims.sales.domain.SalesDocumentLine;
import com.imsvet.ims.sales.infrastructure.CustomerRepository;
import com.imsvet.ims.sales.infrastructure.CustomerReturnLineRepository;
import com.imsvet.ims.sales.infrastructure.CustomerReturnLineRepository.Credited;
import com.imsvet.ims.sales.infrastructure.CustomerReturnRepository;
import com.imsvet.ims.sales.infrastructure.SalesDocumentLineRepository;
import com.imsvet.ims.sales.infrastructure.SalesDocumentLocks;
import com.imsvet.ims.sales.infrastructure.SalesDocumentRepository;
import com.imsvet.ims.settings.application.SettingsProvider;
import com.imsvet.ims.settings.domain.SettingDefinition;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer returns (W3, FR-SALE-006/007, ADR-127…131), one transaction in the lock order of ADR-130: idempotency key (a
 * repeat returns the first return, ADR-104), the original document's row (the lock a cancellation takes, so they never
 * overlap), the numbers ({@code CR}, {@code CN}), the lots in ascending id, then the cash session of a refund. Only a
 * confirmed invoice or receipt is returned (an uninvoiced delivery note is invoiced first), within
 * {@code customer_return_max_days} when set; per sale line, returned ≤ sold (BR-RET-001, backed by a CHECK). A
 * restock goes back into the original lot only when the goods are sealed, the lot not expired and the product not
 * restricted (BR-RET-002, never switched to discard by the server); a discard enters and leaves the lot at once with
 * its reason (BR-RET-003, BR-RET-010). The one credit note is confirmed with the return, its amounts per ADR-130; an
 * immediate sale is refunded in full through {@link RefundRecorder}, a credit sale only credited (BR-RET-006). Audited
 * {@code RETURN_CREATE}. A return is never edited or cancelled (BR-RET-007).
 */
@Service
public class CustomerReturns {

    /** {@code unit}: {@code sold} (default) or {@code base}. */
    public record ReturnLineInput(Long saleLineId, BigDecimal quantity, String unit, Boolean sealed,
            String disposition, String discardReason, String discardNote) {
    }

    public record ReturnInput(Long documentId, String reason, List<ReturnLineInput> lines, List<Tender> refund) {
    }

    public record PreviewInput(Long documentId, List<ReturnLineInput> lines) {
    }

    private record ValidLine(SalesDocumentLine sold, BigDecimal quantityBase, boolean sealed,
            ReturnDisposition disposition, DiscardReason discardReason, String discardNote) {
    }

    private record Priced(ValidLine line, Credit credit) {
    }

    static final String REFERENCE_TYPE = "customer_return_line";
    private static final String ENDPOINT = "POST /api/returns/customer";
    private static final int TEXT_MAX = 500;
    private static final int QUANTITY_SCALE = 3;
    private static final String OFF = "off";
    private static final String SOLD_UNIT = "sold";
    private static final String BASE_UNIT = "base";

    private final SalesDocumentRepository documents;
    private final SalesDocumentLineRepository lines;
    private final SalesDocumentLocks locks;
    private final CustomerReturnRepository returns;
    private final CustomerReturnLineRepository returnLines;
    private final CustomerRepository customers;
    private final ProductLookup products;
    private final ReturnLots lots;
    private final StockService stock;
    private final NumberingService numbering;
    private final PaymentRecorder payments;
    private final RefundRecorder refunds;
    private final IdempotencyKeys keys;
    private final AuditRecorder audit;
    private final SettingsProvider settings;
    private final ActorProvider actors;
    private final BusinessCalendar calendar;
    private final BusinessSettings business;
    private final Clock clock;

    public CustomerReturns(SalesDocumentRepository documents, SalesDocumentLineRepository lines,
            SalesDocumentLocks locks, CustomerReturnRepository returns, CustomerReturnLineRepository returnLines,
            CustomerRepository customers, ProductLookup products, ReturnLots lots, StockService stock,
            NumberingService numbering, PaymentRecorder payments, RefundRecorder refunds, IdempotencyKeys keys,
            AuditRecorder audit, SettingsProvider settings, ActorProvider actors,
            BusinessCalendar calendar, BusinessSettings business, Clock clock) {
        this.documents = documents;
        this.lines = lines;
        this.locks = locks;
        this.returns = returns;
        this.returnLines = returnLines;
        this.customers = customers;
        this.products = products;
        this.lots = lots;
        this.stock = stock;
        this.numbering = numbering;
        this.payments = payments;
        this.refunds = refunds;
        this.keys = keys;
        this.audit = audit;
        this.settings = settings;
        this.actors = actors;
        this.calendar = calendar;
        this.business = business;
        this.clock = clock;
    }

    /**
     * What a return would do, nothing written: every line of the document with what is left to return and whether it
     * may go back into its lot; with {@code lines}, the same checks as a return and the credit note's amounts.
     */
    @PreAuthorize("hasAuthority('return.customer.create')")
    @Transactional(readOnly = true)
    public ReturnPreview preview(PreviewInput in) {
        SalesDocument document = original(in.documentId());
        requireReturnable(document);
        List<SalesDocumentLine> sold = lines.findByDocumentIdOrderByIdAsc(document.id());
        List<ReturnLineInput> requested = in.lines() == null ? List.of() : in.lines();
        List<ValidLine> valid = requested.isEmpty() ? List.of() : validLines(requested, sold);
        Map<Long, ReturnLot> lotInfo = lots.find(sold.stream().map(SalesDocumentLine::lotId).toList());
        Map<Long, ProductRef> productInfo = products.findAll(sold.stream().map(SalesDocumentLine::productId).toList());
        Map<Long, Boolean> sealedBy = valid.stream()
                .collect(Collectors.toMap(v -> v.sold().id(), ValidLine::sealed));
        List<PreviewLine> previewLines = new ArrayList<>();
        for (SalesDocumentLine line : sold) {
            ReturnLot lot = lotInfo.get(line.lotId());
            ProductRef product = productInfo.get(line.productId());
            Optional<RestockRefusal> refusal = RestockRefusal.of(sealedBy.getOrDefault(line.id(), true), lot.expired(),
                    lot.status(), product.returnRestockForbidden());
            previewLines.add(new PreviewLine(line.id(), product(product), lot(lot), line.quantityBase(),
                    line.returnedQuantityBase(), line.quantityBase().subtract(line.returnedQuantityBase()),
                    refusal.isEmpty(), refusal.map(RestockRefusal::code).orElse(null)));
        }
        List<Priced> priced = valid.isEmpty() ? List.of() : price(valid, lotInfo, productInfo);
        List<CreditLine> credits = priced.stream().map(p -> new CreditLine(p.line().sold().id(),
                p.line().quantityBase(), p.credit().ht(), p.credit().vat(), p.credit().ht().add(p.credit().vat())))
                .toList();
        BigDecimal ht = credits.stream().map(CreditLine::lineHt).reduce(zero(), BigDecimal::add);
        BigDecimal vat = credits.stream().map(CreditLine::lineVat).reduce(zero(), BigDecimal::add);
        return new ReturnPreview(original(document), previewLines, new CreditPreview(ht, vat, ht.add(vat), credits),
                !document.creditSale());
    }

    /** {@code idempotencyKey}: the {@code Idempotency-Key} header, {@code null} when absent. */
    @PreAuthorize("hasAuthority('return.customer.create')")
    @Transactional
    public CustomerReturnDetail create(ReturnInput in, String idempotencyKey) {
        String reason = text(in.reason(), "reason", true);
        if (in.documentId() == null) {
            throw invalid("documentId", "REQUIRED", "The original document is required.");
        }
        if (in.lines() == null || in.lines().isEmpty()) {
            throw invalid("lines", "REQUIRED", "A return has at least one line.");
        }
        List<Tender> tenders = in.refund() == null ? List.of() : in.refund();
        Claim claim = keys.claim(actors.current().userId(), ENDPOINT, idempotencyKey,
                new ReturnInput(in.documentId(), reason, in.lines(), tenders), "customer_return");
        if (claim.replayed().isPresent()) {
            return detail(returns.findById(claim.replayed().get()).orElseThrow());
        }
        SalesDocument document = original(in.documentId());
        locks.lockAndRefresh(document);
        requireReturnable(document);
        List<SalesDocumentLine> sold = lines.findByDocumentIdOrderByIdAsc(document.id());
        List<ValidLine> valid = validLines(in.lines(), sold);
        if (document.creditSale() && !tenders.isEmpty()) {
            throw new BusinessException(ErrorCode.REFUND_TOTAL_MISMATCH,
                    "A return on a credit sale is credited to the account, not refunded.",
                    Map.of("expected", "0.00", "given", tenders.stream().map(Tender::amount)
                            .filter(a -> a != null).reduce(BigDecimal.ZERO, BigDecimal::add).toPlainString()));
        }

        String number = numbering.next(DocumentKey.CR);
        String creditNumber = numbering.next(DocumentKey.CN);
        Map<Long, ReturnLot> locked = lots.lock(valid.stream().map(v -> v.sold().lotId()).toList());
        Map<Long, ProductRef> productInfo = products.findAll(valid.stream().map(v -> v.sold().productId()).toList());
        List<Priced> priced = price(valid, locked, productInfo);

        Instant now = clock.instant();
        long actor = actors.current().userId();
        SalesDocument creditNote = documents.saveAndFlush(SalesDocument.creditNoteOf(document, actor, now));
        List<SalesDocumentLine> creditLines = new ArrayList<>();
        for (Priced p : priced) {
            creditLines.add(lines.saveAndFlush(SalesDocumentLine.creditFor(p.line().sold(), creditNote.id(),
                    p.credit().quantity(), p.line().quantityBase(), p.credit().ht(), p.credit().vat())));
        }
        SaleTotals.apply(creditNote, creditLines, now);
        CustomerReturn created = returns.saveAndFlush(new CustomerReturn(number, document.id(), creditNote.id(),
                reason, actor, now));
        List<CustomerReturnLine> saved = new ArrayList<>();
        List<Movement> movements = new ArrayList<>();
        for (int i = 0; i < priced.size(); i++) {
            ValidLine v = priced.get(i).line();
            CustomerReturnLine line = returnLines.saveAndFlush(new CustomerReturnLine(created.id(), v.sold().id(),
                    v.sold().lotId(), v.sold().productId(), v.quantityBase(), v.sealed(), v.disposition(),
                    v.discardReason(), v.discardNote(), creditLines.get(i).id()));
            saved.add(line);
            movements.add(new Movement(line.lotId(), line.productId(), MovementType.CUSTOMER_RETURN,
                    line.quantityBase(), null, REFERENCE_TYPE, line.id()));
            if (v.disposition() == ReturnDisposition.DISCARD) {
                movements.add(new Movement(line.lotId(), line.productId(), MovementType.RETURN_DISCARD,
                        line.quantityBase().negate(), movementReason(v), REFERENCE_TYPE, line.id()));
            }
            v.sold().recordReturn(v.quantityBase());
            lines.saveAndFlush(v.sold());
        }
        stock.post(movements);

        Long cashSession = null;
        if (!document.creditSale()) {
            List<String> originalMethods = payments.inForceOn(document.id()).stream()
                    .map(PaymentRecorder.PaymentInForce::method).distinct().toList();
            List<RecordedRefund> paid = refunds.refund(new RefundRequest(creditNote.id(), document.id(),
                    document.customerId(), creditNote.totalTtc(), tenders, originalMethods));
            cashSession = paid.stream().map(RecordedRefund::cashSessionId).filter(s -> s != null).findFirst()
                    .orElse(null);
        }
        creditNote.confirm(creditNumber, cashSession, now);
        claim.complete(created.id());
        documents.saveAndFlush(creditNote);
        audit(created, document, creditNote, saved);
        return detail(created);
    }

    /** Every filter optional; {@code from}/{@code to} business dates, both inclusive; newest first. */
    @PreAuthorize("hasAuthority('return.read')")
    @Transactional(readOnly = true)
    public PageResponse<CustomerReturnSummary> search(Long customerId, Long documentId, LocalDate from, LocalDate to,
            int page, int size) {
        Page<CustomerReturn> found = returns.search(customerId, documentId, startOf(from),
                to == null ? null : startOf(to.plusDays(1)), PageRequests.of(page, size, Sort.unsorted()));
        return PageResponse.of(found, r -> {
            SalesDocument document = documents.findById(r.documentId()).orElseThrow();
            SalesDocument note = documents.findById(r.creditNoteId()).orElseThrow();
            return new CustomerReturnSummary(r.id(), r.number(), new DocumentRef(document.id(), document.number(),
                    document.docType().code()), customer(document), new CreditNoteRef(note.id(), note.number(),
                    note.totalHt(), note.totalVat(), note.totalTtc()), !document.creditSale(), r.reason(),
                    r.createdAt(), r.createdBy());
        });
    }

    @PreAuthorize("hasAuthority('return.read')")
    @Transactional(readOnly = true)
    public CustomerReturnDetail get(long id) {
        return detail(returns.findById(id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Customer return not found.", Map.of("id", id))));
    }

    private Instant startOf(LocalDate day) {
        return day == null ? null : day.atStartOfDay(business.timeZone()).toInstant();
    }

    private SalesDocument original(Long id) {
        if (id == null) {
            throw invalid("documentId", "REQUIRED", "The original document is required.");
        }
        return documents.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "Sales document not found.", Map.of("id", id)));
    }

    /** BR-RET-009, ADR-131. */
    private void requireReturnable(SalesDocument document) {
        if (document.docType() == SalesDocType.DELIVERY_NOTE && document.status() == SalesDocStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.RETURN_REQUIRES_INVOICE,
                    "Invoice this delivery note first, then return against the invoice.",
                    Map.of("documentId", document.id()));
        }
        boolean returnable = document.docType() == SalesDocType.INVOICE || document.docType() == SalesDocType.RECEIPT;
        if (!returnable || document.status() != SalesDocStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only a confirmed invoice or receipt can be returned.",
                    Map.of("docType", document.docType().code(), "status", document.status().code(),
                            "action", "return"));
        }
        String maxDays = settings.value(SettingDefinition.CUSTOMER_RETURN_MAX_DAYS);
        if (!OFF.equals(maxDays)) {
            int days = Integer.parseInt(maxDays);
            if (document.issuedAt().isBefore(calendar.startOf(calendar.today().minusDays(days)))) {
                LocalDate issuedOn = document.issuedAt().atZone(business.timeZone()).toLocalDate();
                throw new BusinessException(ErrorCode.RETURN_PERIOD_EXPIRED,
                        "This sale is too old to be returned.",
                        Map.of("maxDays", days, "issuedOn", issuedOn.toString()));
            }
        }
    }

    /** Shape, quantities (BR-RET-001) and disposition fields (BR-RET-010) of every requested line. */
    private List<ValidLine> validLines(List<ReturnLineInput> requested, List<SalesDocumentLine> sold) {
        Map<Long, SalesDocumentLine> byId = sold.stream()
                .collect(Collectors.toMap(SalesDocumentLine::id, Function.identity()));
        Map<Long, ProductRef> productInfo = products.findAll(sold.stream().map(SalesDocumentLine::productId).toList());
        Set<Long> seen = new HashSet<>();
        List<ValidLine> valid = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            String prefix = "lines[" + i + "].";
            ReturnLineInput in = requested.get(i);
            if (in.saleLineId() == null) {
                throw invalid(prefix + "saleLineId", "REQUIRED", "The sale line is required.");
            }
            SalesDocumentLine line = byId.get(in.saleLineId());
            if (line == null) {
                boolean exists = lines.findById(in.saleLineId()).isPresent();
                throw invalid(prefix + "saleLineId", exists ? "OTHER_DOCUMENT" : "UNKNOWN",
                        exists ? "This line belongs to another document." : "Unknown sale line.");
            }
            if (!seen.add(line.id())) {
                throw invalid(prefix + "saleLineId", "DUPLICATE", "Each sale line is returned once per return.");
            }
            BigDecimal base = quantityBase(in, line, productInfo.get(line.productId()), prefix);
            BigDecimal returnable = line.quantityBase().subtract(line.returnedQuantityBase());
            if (base.compareTo(returnable) > 0) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITY_EXCEEDED,
                        "More than what is left to return of this line.",
                        Map.of("saleLineId", line.id(), "returnableBase", returnable.toPlainString()));
            }
            if (in.sealed() == null) {
                throw invalid(prefix + "sealed", "REQUIRED", "Say whether the goods are sealed.");
            }
            ReturnDisposition disposition = in.disposition() == null ? null
                    : ReturnDisposition.fromCode(in.disposition()).orElseThrow(
                            () -> invalid(prefix + "disposition", "UNKNOWN", "Unknown disposition."));
            if (disposition == null) {
                throw invalid(prefix + "disposition", "REQUIRED", "Choose restock or discard.");
            }
            DiscardReason reason = discardReason(in, disposition, prefix);
            String note = discardNote(in, reason, prefix);
            valid.add(new ValidLine(line, base, in.sealed(), disposition, reason, note));
        }
        return valid;
    }

    private static BigDecimal quantityBase(ReturnLineInput in, SalesDocumentLine line, ProductRef product,
            String prefix) {
        BigDecimal quantity = in.quantity();
        if (quantity == null) {
            throw invalid(prefix + "quantity", "REQUIRED", "The quantity is required.");
        }
        if (quantity.signum() <= 0) {
            throw invalid(prefix + "quantity", "POSITIVE", "The quantity must be greater than zero.");
        }
        String unit = in.unit() == null ? SOLD_UNIT : in.unit();
        BigDecimal base;
        if (SOLD_UNIT.equals(unit)) {
            base = quantity.multiply(line.quantityBase()).divide(line.quantity(), MathContext.DECIMAL128);
        } else if (BASE_UNIT.equals(unit)) {
            base = quantity;
        } else {
            throw invalid(prefix + "unit", "UNKNOWN", "The unit is sold or base.");
        }
        base = base.stripTrailingZeros();
        if (base.scale() > QUANTITY_SCALE) {
            throw invalid(prefix + "quantity", "SCALE", "At most three decimals in the base unit.");
        }
        if (!product.divisible() && base.scale() > 0) {
            throw invalid(prefix + "quantity", "WHOLE_NUMBER", "This product is returned in whole base units.");
        }
        return base.setScale(QUANTITY_SCALE);
    }

    private static DiscardReason discardReason(ReturnLineInput in, ReturnDisposition disposition, String prefix) {
        if (disposition == ReturnDisposition.RESTOCK) {
            if (in.discardReason() != null || in.discardNote() != null) {
                throw invalid(prefix + "discardReason", "NOT_ALLOWED", "A restocked line has no discard reason.");
            }
            return null;
        }
        if (in.discardReason() == null) {
            throw invalid(prefix + "discardReason", "REQUIRED", "A discarded line needs its reason.");
        }
        return DiscardReason.fromCode(in.discardReason())
                .orElseThrow(() -> invalid(prefix + "discardReason", "UNKNOWN", "Unknown discard reason."));
    }

    private static String discardNote(ReturnLineInput in, DiscardReason reason, String prefix) {
        if (reason == null) {
            return null;
        }
        return text(in.discardNote(), prefix + "discardNote", reason == DiscardReason.OTHER);
    }

    /** BR-RET-002 per line, on locked (create) or read (preview) lots, then the credit of ADR-130. */
    private List<Priced> price(List<ValidLine> valid, Map<Long, ReturnLot> lotInfo,
            Map<Long, ProductRef> productInfo) {
        List<Priced> priced = new ArrayList<>();
        for (ValidLine v : valid) {
            if (v.disposition() == ReturnDisposition.RESTOCK) {
                ReturnLot lot = lotInfo.get(v.sold().lotId());
                Optional<RestockRefusal> refusal = RestockRefusal.of(v.sealed(), lot.expired(), lot.status(),
                        productInfo.get(v.sold().productId()).returnRestockForbidden());
                if (refusal.isPresent()) {
                    throw new BusinessException(ErrorCode.RETURN_NOT_RESTOCKABLE,
                            "These goods cannot go back into stock; discard them with a reason.",
                            Map.of("saleLineId", v.sold().id(), "reason", refusal.get().code()));
                }
            }
            Credited credited = returnLines.creditedOn(v.sold().id());
            SalesDocumentLine s = v.sold();
            priced.add(new Priced(v, ReturnCredit.of(new SoldLine(s.quantity(), s.quantityBase(), s.unitPrice(),
                    s.discountPct(), s.vatRate(), s.returnedQuantityBase(), credited.getHt(), credited.getVat(),
                    s.globalDiscountHt()),
                    v.quantityBase())));
        }
        return priced;
    }

    private static String movementReason(ValidLine v) {
        return v.discardNote() == null ? v.discardReason().code() : v.discardReason().code() + ": " + v.discardNote();
    }

    private void audit(CustomerReturn created, SalesDocument document, SalesDocument creditNote,
            List<CustomerReturnLine> saved) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("number", created.number());
        values.put("documentId", document.id());
        values.put("creditNoteId", creditNote.id());
        values.put("creditNoteNumber", creditNote.number());
        values.put("totalTtc", creditNote.totalTtc().toPlainString());
        values.put("reason", created.reason());
        values.put("lines", saved.stream().map(l -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("saleLineId", l.saleLineId());
            line.put("lotId", l.lotId());
            line.put("quantityBase", l.quantityBase().toPlainString());
            line.put("sealed", l.sealed());
            line.put("disposition", l.disposition().code());
            if (l.discardReason() != null) {
                line.put("discardReason", l.discardReason().code());
            }
            if (l.discardNote() != null) {
                line.put("discardNote", l.discardNote());
            }
            return line;
        }).toList());
        audit.record(AuditAction.RETURN_CREATE, "customer_returns", created.id(), null, values);
    }

    private CustomerReturnDetail detail(CustomerReturn r) {
        SalesDocument document = documents.findById(r.documentId()).orElseThrow();
        SalesDocument creditNote = documents.findById(r.creditNoteId()).orElseThrow();
        List<CustomerReturnLine> saved = returnLines.findByReturnIdOrderByIdAsc(r.id());
        Map<Long, ReturnLot> lotInfo = lots.find(saved.stream().map(CustomerReturnLine::lotId).toList());
        Map<Long, ProductRef> productInfo = products.findAll(saved.stream().map(CustomerReturnLine::productId)
                .toList());
        Map<Long, List<Long>> movementIds = lots.movementIds(REFERENCE_TYPE,
                saved.stream().map(CustomerReturnLine::id).toList());
        return new CustomerReturnDetail(r.id(), r.number(), new DocumentRef(document.id(), document.number(),
                document.docType().code()), customer(document), r.reason(), r.createdAt(),
                r.createdBy(), new CreditNoteRef(creditNote.id(), creditNote.number(),
                        creditNote.totalHt(), creditNote.totalVat(), creditNote.totalTtc()),
                saved.stream().map(l -> new ReturnedLine(l.id(), l.saleLineId(),
                        product(productInfo.get(l.productId())), lot(lotInfo.get(l.lotId())), l.quantityBase(),
                        l.sealed(), l.disposition().code(),
                        l.discardReason() == null ? null : l.discardReason().code(), l.discardNote(),
                        movementIds.getOrDefault(l.id(), List.of()))).toList(),
                refunds.refundsOf(creditNote.id()).stream().map(f -> new RefundItem(f.id(), f.method(), f.amount(),
                        f.reference(), f.cashSessionId())).toList());
    }

    private OriginalDocument original(SalesDocument document) {
        return new OriginalDocument(document.id(), document.number(), document.docType().code(),
                document.creditSale(), customer(document), document.issuedAt());
    }

    private CustomerRef customer(SalesDocument document) {
        return document.customerId() == null ? null : customers.findById(document.customerId())
                .map(c -> new CustomerRef(c.id(), c.name())).orElseThrow();
    }

    private static ProductItem product(ProductRef p) {
        return new ProductItem(p.id(), p.sku(), p.commercialName(), p.baseUnit());
    }

    private static LotItem lot(ReturnLot l) {
        return new LotItem(l.id(), l.lotNumber(), l.expiryDate(), l.status());
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    private static String text(String value, String field, boolean required) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            if (required) {
                throw invalid(field, "REQUIRED", "This text is required.");
            }
            return null;
        }
        if (trimmed.length() > TEXT_MAX) {
            throw invalid(field, "SIZE", "At most " + TEXT_MAX + " characters.");
        }
        return trimmed;
    }

    private static ValidationException invalid(String field, String code, String message) {
        return new ValidationException("The customer return is invalid.", List.of(new Violation(field, code, message)));
    }
}
