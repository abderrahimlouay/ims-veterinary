package com.imsvet.ims.sales.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.catalog.application.ProductWithdrawals;
import com.imsvet.ims.catalog.domain.UnitConversion;
import com.imsvet.ims.catalog.domain.UnitConversion.InexactConversionException;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.security.Permissions;
import com.imsvet.ims.inventory.application.SaleLots;
import com.imsvet.ims.inventory.application.SaleLots.LotPick;
import com.imsvet.ims.inventory.application.SaleLots.Proposal;
import com.imsvet.ims.sales.application.SalesViews.SaleDetail;
import com.imsvet.ims.sales.application.SalesViews.SaleWarning;
import com.imsvet.ims.sales.domain.PriceResolver.Resolution;
import com.imsvet.ims.sales.domain.PriceResolver.Source;
import com.imsvet.ims.sales.domain.SalesDocStatus;
import com.imsvet.ims.sales.domain.SalesDocType;
import com.imsvet.ims.sales.domain.SalesDocument;
import com.imsvet.ims.sales.domain.SalesDocumentLine;
import com.imsvet.ims.sales.infrastructure.SalesDocumentLineRepository;
import com.imsvet.ims.sales.infrastructure.SalesDocumentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lines of a draft (W2 steps 3–4). The server resolves the price (BR-SALE-006, ADR-107), the VAT rate and the lots:
 * FEFO splits a quantity across lots into several lines (BR-LOT-005/006), net of what the draft already holds. Another
 * lot needs {@code sale.override_fefo} and a reason (ADR-120). A quote has no lot and checks no stock (BR-SALE-003).
 * Overrides are audited in the same transaction; totals are recomputed from the rounded lines (BR-SALE-008).
 */
@Service
public class SaleLines {

    public record LineInput(Long productId, Long unitId, BigDecimal quantity, Integer targetSpeciesId, Long lotId,
            String fefoOverrideReason, BigDecimal manualDiscountPct, BigDecimal manualUnitPrice) {
    }

    private static final String OVERRIDE_FEFO = "sale.override_fefo";
    private static final int PRICE_SCALE = 4;
    private static final int DISCOUNT_SCALE = 2;
    private static final int REASON_MAX = 500;

    private final SalesDocumentRepository documents;
    private final SalesDocumentLineRepository lines;
    private final CustomerPricing pricing;
    private final ProductLookup products;
    private final ProductWithdrawals withdrawals;
    private final SaleLots lots;
    private final SaleViewer viewer;
    private final AuditRecorder audit;
    private final Clock clock;

    public SaleLines(SalesDocumentRepository documents, SalesDocumentLineRepository lines, CustomerPricing pricing,
            ProductLookup products, ProductWithdrawals withdrawals, SaleLots lots, SaleViewer viewer,
            AuditRecorder audit, Clock clock) {
        this.documents = documents;
        this.lines = lines;
        this.pricing = pricing;
        this.products = products;
        this.withdrawals = withdrawals;
        this.lots = lots;
        this.viewer = viewer;
        this.audit = audit;
        this.clock = clock;
    }

    @PreAuthorize("hasAuthority('sale.create')")
    @Transactional
    public SaleDetail add(long documentId, LineInput in) {
        SalesDocument document = draft(documentId);
        addLines(document, in);
        return saved(document);
    }

    /** Replaces a line: the same input as adding one, so it may split again. */
    @PreAuthorize("hasAuthority('sale.create')")
    @Transactional
    public SaleDetail replace(long documentId, long lineId, LineInput in) {
        SalesDocument document = draft(documentId);
        lines.delete(line(documentId, lineId));
        lines.flush();
        addLines(document, in);
        return saved(document);
    }

    @PreAuthorize("hasAuthority('sale.create')")
    @Transactional
    public SaleDetail remove(long documentId, long lineId) {
        SalesDocument document = draft(documentId);
        lines.delete(line(documentId, lineId));
        lines.flush();
        return saved(document);
    }

    @PreAuthorize("hasAuthority('sale.read')")
    @Transactional(readOnly = true)
    public List<SaleWarning> withdrawalWarnings(long documentId) {
        return viewer.withdrawalWarnings(find(documentId));
    }

    /** A quote's line on its derived draft: FEFO lots, split if needed, at the quoted price and VAT (W11). */
    void copyWithLots(SalesDocument document, SalesDocumentLine quoted) {
        LineInput in = new LineInput(quoted.productId(), quoted.unitId(), quoted.quantity(), quoted.targetSpeciesId(),
                null, null, null, null);
        BigDecimal factor = quoted.unitId() == null ? BigDecimal.ONE
                : products.unit(quoted.productId(), quoted.unitId()).orElseThrow().factorToBase();
        for (LotPick pick : picks(document, in, quoted.quantityBase())) {
            lines.saveAndFlush(new SalesDocumentLine(document.id(), quoted.productId(), pick.lotId(), quoted.unitId(),
                    quoted.targetSpeciesId(), soldQuantity(pick, quoted.quantityBase(), quoted.quantity(), factor)
                            .setScale(UnitConversion.BASE_SCALE),
                    pick.quantityBase(), quoted.unitPrice(), quoted.discountPct(), quoted.vatRate()));
        }
    }

    private void addLines(SalesDocument document, LineInput in) {
        if (in.productId() == null) {
            throw invalid("productId", "REQUIRED", "The product is required.");
        }
        checkManualValues(in);
        if (in.targetSpeciesId() != null && !withdrawals.speciesExists(in.targetSpeciesId())) {
            throw invalid("targetSpeciesId", "UNKNOWN", "Unknown species.");
        }
        Resolution price = pricing.resolve(document.customerId(), in.productId(), in.unitId(), in.manualUnitPrice(),
                in.manualDiscountPct());
        ProductRef product = products.find(in.productId()).orElseThrow();
        BigDecimal factor = in.unitId() == null ? BigDecimal.ONE
                : products.unit(in.productId(), in.unitId()).orElseThrow().factorToBase();
        BigDecimal requested = toBase(in.quantity(), factor, product.divisible());
        auditPricing(document, in, price);
        BigDecimal unitPrice = price.unitPrice().setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        BigDecimal discount = price.discountPct().setScale(DISCOUNT_SCALE, RoundingMode.HALF_UP);
        if (document.docType() == SalesDocType.QUOTE) {
            if (in.lotId() != null) {
                throw invalid("lotId", "NOT_ALLOWED", "A quote has no lot.");
            }
            lines.saveAndFlush(line(document, in, null, in.quantity(), requested, unitPrice, discount, product));
            return;
        }
        for (LotPick pick : picks(document, in, requested)) {
            lines.saveAndFlush(line(document, in, pick.lotId(), soldQuantity(pick, requested, in.quantity(), factor),
                    pick.quantityBase(), unitPrice, discount, product));
        }
    }

    private static SalesDocumentLine line(SalesDocument document, LineInput in, Long lotId, BigDecimal quantity,
            BigDecimal quantityBase, BigDecimal unitPrice, BigDecimal discount, ProductRef product) {
        return new SalesDocumentLine(document.id(), in.productId(), lotId, in.unitId(), in.targetSpeciesId(),
                quantity.setScale(UnitConversion.BASE_SCALE), quantityBase, unitPrice, discount, product.vatRate());
    }

    private List<LotPick> picks(SalesDocument document, LineInput in, BigDecimal requested) {
        Map<Long, BigDecimal> held = held(document.id());
        Proposal proposal = lots.propose(in.productId(), requested, held);
        if (in.lotId() == null) {
            if (proposal.shortfallBase().signum() > 0) {
                throw new BusinessException(ErrorCode.STOCK_INSUFFICIENT, "Not enough stock for this quantity.",
                        Map.of("productId", in.productId(), "shortfall", proposal.shortfallBase().toPlainString()));
            }
            return proposal.picks();
        }
        lots.requireSellable(in.lotId(), in.productId(), requested, held);
        boolean isProposal = proposal.picks().size() == 1 && proposal.picks().get(0).lotId() == in.lotId();
        if (!isProposal) {
            authoriseOverride(document, in, proposal);
        }
        return List.of(new LotPick(in.lotId(), requested));
    }

    private void authoriseOverride(SalesDocument document, LineInput in, Proposal proposal) {
        if (!Permissions.granted(OVERRIDE_FEFO)) {
            throw new BusinessException(ErrorCode.FEFO_VIOLATION, "Another lot needs the FEFO override permission.",
                    Map.of("lotId", in.lotId()));
        }
        String reason = in.fefoOverrideReason() == null ? "" : in.fefoOverrideReason().trim();
        if (reason.isEmpty()) {
            throw invalid("fefoOverrideReason", "REQUIRED", "A reason is required to choose another lot.");
        }
        if (reason.length() > REASON_MAX) {
            throw invalid("fefoOverrideReason", "SIZE", "At most " + REASON_MAX + " characters.");
        }
        Map<String, Object> proposed = new LinkedHashMap<>();
        proposed.put("proposedLotIds", proposal.picks().stream().map(LotPick::lotId).toList());
        audit.record(AuditAction.FEFO_OVERRIDE, "sales_documents", document.id(), proposed,
                Map.of("productId", in.productId(), "lotId", in.lotId(), "reason", reason));
    }

    private void auditPricing(SalesDocument document, LineInput in, Resolution price) {
        if (price.source() == Source.MANUAL) {
            Resolution resolved = pricing.resolve(document.customerId(), in.productId(), in.unitId(), null, null);
            audit.record(AuditAction.PRICE_CHANGE, "sales_documents", document.id(),
                    Map.of("unitPrice", resolved.unitPrice().toPlainString()),
                    Map.of("productId", in.productId(), "unitPrice", price.unitPrice().toPlainString()));
        }
        if (price.discountPct().compareTo(price.entitlementPct()) > 0) {
            audit.record(AuditAction.DISCOUNT_OVERRIDE, "sales_documents", document.id(),
                    Map.of("discountPct", price.entitlementPct().toPlainString()),
                    Map.of("productId", in.productId(), "discountPct", price.discountPct().toPlainString()));
        }
    }

    private static void checkManualValues(LineInput in) {
        if (in.manualUnitPrice() != null && (in.manualUnitPrice().signum() < 0
                || in.manualUnitPrice().stripTrailingZeros().scale() > PRICE_SCALE)) {
            throw invalid("manualUnitPrice", "RANGE", "A price is zero or more, with at most four decimals.");
        }
        if (in.manualDiscountPct() != null && in.manualDiscountPct().stripTrailingZeros().scale() > DISCOUNT_SCALE) {
            throw invalid("manualDiscountPct", "SCALE", "At most two decimals.");
        }
    }

    private static BigDecimal toBase(BigDecimal quantity, BigDecimal factor, boolean divisible) {
        if (quantity == null) {
            throw invalid("quantity", "REQUIRED", "The quantity is required.");
        }
        if (quantity.stripTrailingZeros().scale() > UnitConversion.BASE_SCALE) {
            throw invalid("quantity", "SCALE", "At most three decimals.");
        }
        try {
            return UnitConversion.toBase(quantity, factor, divisible);
        } catch (InexactConversionException e) {
            throw switch (e.refusal()) {
                case NOT_POSITIVE -> invalid("quantity", "POSITIVE", "The quantity must be greater than zero.");
                case SCALE -> invalid("quantity", "SCALE", "This quantity cannot be converted exactly.");
                case WHOLE_NUMBER -> invalid("quantity", "WHOLE_NUMBER", "This product is counted in whole units.");
            };
        }
    }

    /** A split part in the sold unit; a part the unit cannot express exactly has to be sold in the base unit. */
    private static BigDecimal soldQuantity(LotPick pick, BigDecimal requested, BigDecimal quantity, BigDecimal factor) {
        if (pick.quantityBase().compareTo(requested) == 0) {
            return quantity;
        }
        BigDecimal sold = pick.quantityBase().divide(factor, UnitConversion.BASE_SCALE, RoundingMode.HALF_UP);
        if (sold.multiply(factor).compareTo(pick.quantityBase()) != 0) {
            throw invalid("quantity", "SPLIT_INEXACT",
                    "This quantity spans lots in parts this unit cannot express; sell it in the base unit.");
        }
        return sold;
    }

    private Map<Long, BigDecimal> held(long documentId) {
        Map<Long, BigDecimal> held = new HashMap<>();
        lines.findByDocumentIdOrderByIdAsc(documentId).stream().filter(l -> l.lotId() != null)
                .forEach(l -> held.merge(l.lotId(), l.quantityBase(), BigDecimal::add));
        return held;
    }

    private SaleDetail saved(SalesDocument document) {
        List<SalesDocumentLine> all = lines.findByDocumentIdOrderByIdAsc(document.id());
        SaleTotals.apply(document, all, clock.instant());
        return viewer.detail(documents.saveAndFlush(document));
    }

    private SalesDocument draft(long id) {
        SalesDocument document = find(id);
        if (document.status() != SalesDocStatus.DRAFT) {
            throw new BusinessException(ErrorCode.DOCUMENT_IMMUTABLE, "Only a draft can be changed.",
                    Map.of("status", document.status().code()));
        }
        if (documents.invoicesADeliveryNote(document)) {
            throw new BusinessException(ErrorCode.DOCUMENT_IMMUTABLE,
                    "The lines of a delivery note's invoice are frozen.", Map.of("parentId", document.parentId()));
        }
        return document;
    }

    private SalesDocument find(long id) {
        return documents.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "Sales document not found.", Map.of("id", id)));
    }

    private SalesDocumentLine line(long documentId, long lineId) {
        return lines.findByIdAndDocumentId(lineId, documentId).orElseThrow(() -> new BusinessException(
                ErrorCode.NOT_FOUND, "Line not found.", Map.of("id", lineId)));
    }

    private static ValidationException invalid(String field, String code, String message) {
        return new ValidationException("The sales line is invalid.", List.of(new Violation(field, code, message)));
    }
}
