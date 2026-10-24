package com.imsvet.ims.purchasing.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.common.time.BusinessSettings;
import com.imsvet.ims.inventory.application.LotReferences;
import com.imsvet.ims.inventory.application.LotReferences.LotRef;
import com.imsvet.ims.inventory.application.StockService;
import com.imsvet.ims.inventory.application.StockService.Movement;
import com.imsvet.ims.inventory.domain.MovementType;
import com.imsvet.ims.numbering.application.NumberingService;
import com.imsvet.ims.numbering.domain.DocumentKey;
import com.imsvet.ims.purchasing.application.OrderViews.SupplierRef;
import com.imsvet.ims.purchasing.application.OrderViews.UserRef;
import com.imsvet.ims.purchasing.application.ReturnViews.ReturnLine;
import com.imsvet.ims.purchasing.application.ReturnViews.SupplierReturnDetail;
import com.imsvet.ims.purchasing.application.ReturnViews.SupplierReturnSummary;
import com.imsvet.ims.purchasing.domain.Supplier;
import com.imsvet.ims.purchasing.domain.SupplierReturn;
import com.imsvet.ims.purchasing.domain.SupplierReturnLine;
import com.imsvet.ims.purchasing.infrastructure.SupplierRepository;
import com.imsvet.ims.purchasing.infrastructure.SupplierReturnRepository;
import com.imsvet.ims.users.application.UserNames;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier returns (FR-PURCH-006, W4, ADR-101), one transaction: a known supplier (an inactive one too), a reason,
 * lots of that supplier or without supplier ({@code OTHER_SUPPLIER}) in any status, once each, quantities in a unit
 * of the lot's product converted exactly; then the SR number (ADR-097), the return audited {@code RETURN_CREATE},
 * and one {@code supplier_return} movement per line through {@link StockService}, which locks the lots and refuses
 * more than their balance ({@code STOCK_INSUFFICIENT}, BR-STOCK-001). Stock only: the supplier credit is Phase 9.
 */
@Service
public class SupplierReturns {

    public record ReturnInput(Long supplierId, String reason, List<ReturnedLine> lines) {
    }

    public record ReturnedLine(Long lotId, BigDecimal quantity, Long unitId) {
    }

    private record ValidLine(LotRef lot, BigDecimal quantityBase) {
    }

    private final SupplierReturnRepository returns;
    private final SupplierDirectory suppliers;
    private final SupplierRepository supplierRows;
    private final LotReferences lots;
    private final ProductLookup products;
    private final StockService stock;
    private final NumberingService numbering;
    private final AuditRecorder audit;
    private final UserNames userNames;
    private final ActorProvider actors;
    private final BusinessSettings business;
    private final Clock clock;

    public SupplierReturns(SupplierReturnRepository returns, SupplierDirectory suppliers,
            SupplierRepository supplierRows, LotReferences lots, ProductLookup products, StockService stock,
            NumberingService numbering, AuditRecorder audit, UserNames userNames, ActorProvider actors,
            BusinessSettings business, Clock clock) {
        this.returns = returns;
        this.suppliers = suppliers;
        this.supplierRows = supplierRows;
        this.lots = lots;
        this.products = products;
        this.stock = stock;
        this.numbering = numbering;
        this.audit = audit;
        this.userNames = userNames;
        this.actors = actors;
        this.business = business;
        this.clock = clock;
    }

    /** Every filter optional; {@code from}/{@code to} business dates, both inclusive; newest first. */
    @PreAuthorize("hasAuthority('return.read')")
    @Transactional(readOnly = true)
    public PageResponse<SupplierReturnSummary> search(Long supplierId, LocalDate from, LocalDate to, int page,
            int size) {
        Page<SupplierReturn> found = returns.search(supplierId, startOf(from),
                to == null ? null : startOf(to.plusDays(1)), PageRequests.of(page, size, Sort.unsorted()));
        return PageResponse.of(found, r -> new SupplierReturnSummary(r.id(), r.number(), supplier(r.supplierId()),
                r.reason(), r.createdAt()));
    }

    @PreAuthorize("hasAuthority('return.read')")
    @Transactional(readOnly = true)
    public SupplierReturnDetail get(long id) {
        return detail(returns.findById(id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Supplier return not found.", Map.of("id", id))));
    }

    @PreAuthorize("hasAuthority('return.supplier.create')")
    @Transactional
    public SupplierReturnDetail create(ReturnInput in) {
        if (in.supplierId() == null) {
            throw OrderLineValidator.invalid("supplierId", "REQUIRED", "The supplier is required.");
        }
        Supplier supplier = suppliers.findOptional(in.supplierId())
                .orElseThrow(() -> OrderLineValidator.invalid("supplierId", "UNKNOWN", "Unknown supplier."));
        if (in.reason() == null || in.reason().isBlank()) {
            throw OrderLineValidator.invalid("reason", "REQUIRED", "A reason is required.");
        }
        List<ValidLine> lines = lines(in.lines(), supplier.id());

        String number = numbering.next(DocumentKey.SR);
        Long userId = actors.current().userId();
        SupplierReturn created = returns.saveAndFlush(new SupplierReturn(number, supplier.id(), in.reason().trim(),
                userId, clock.instant()));
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("number", number);
        newValues.put("supplierId", supplier.id());
        newValues.put("reason", created.reason());
        newValues.put("lines", lines.stream().map(l -> Map.of("lotId", l.lot().id(), "quantityBase",
                l.quantityBase().toPlainString())).toList());
        audit.record(AuditAction.RETURN_CREATE, "supplier_returns", created.id(), null, newValues);
        List<Long> movementIds = stock.post(lines.stream().map(l -> new Movement(l.lot().id(), l.lot().productId(),
                MovementType.SUPPLIER_RETURN, l.quantityBase().negate(), null, "supplier_return", created.id()))
                .toList());
        List<SupplierReturn.LineInput> inputs = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            ValidLine l = lines.get(i);
            inputs.add(new SupplierReturn.LineInput(l.lot().id(), l.lot().productId(), l.quantityBase(),
                    movementIds.get(i)));
        }
        created.addLines(inputs);
        returns.flush();
        return detail(created);
    }

    private List<ValidLine> lines(List<ReturnedLine> lines, long supplierId) {
        if (lines == null || lines.isEmpty()) {
            throw OrderLineValidator.invalid("lines", "REQUIRED", "A return has at least one line.");
        }
        Map<Long, LotRef> refs = lots.find(lines.stream().map(ReturnedLine::lotId).filter(id -> id != null).toList());
        List<ValidLine> valid = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String prefix = "lines[" + i + "].";
            ReturnedLine in = lines.get(i);
            if (in.lotId() == null) {
                throw OrderLineValidator.invalid(prefix + "lotId", "REQUIRED", "The lot is required.");
            }
            LotRef lot = refs.get(in.lotId());
            if (lot == null) {
                throw OrderLineValidator.invalid(prefix + "lotId", "UNKNOWN", "Unknown lot.");
            }
            if (lot.supplierId() != null && lot.supplierId() != supplierId) {
                throw OrderLineValidator.invalid(prefix + "lotId", "OTHER_SUPPLIER",
                        "This lot was received from another supplier.");
            }
            if (!seen.add(lot.id())) {
                throw OrderLineValidator.invalid(prefix + "lotId", "DUPLICATE", "A lot appears once per return.");
            }
            ProductRef product = products.find(lot.productId()).orElseThrow();
            valid.add(new ValidLine(lot, BaseQuantities.toBase(products, product, in.unitId(), in.quantity(), prefix)));
        }
        return valid;
    }

    private SupplierReturnDetail detail(SupplierReturn r) {
        List<SupplierReturnLine> lines = r.lines();
        Map<Long, ProductRef> refs = products.findAll(lines.stream().map(SupplierReturnLine::productId)
                .collect(Collectors.toSet()));
        Map<Long, LotRef> lotRefs = lots.find(lines.stream().map(SupplierReturnLine::lotId).toList());
        String creator = userNames.fullNames(List.of(r.createdBy())).get(r.createdBy());
        return new SupplierReturnDetail(r.id(), r.number(), supplier(r.supplierId()), r.reason(), r.createdAt(),
                new UserRef(r.createdBy(), creator), lines.stream().map(l -> {
                    ProductRef p = refs.get(l.productId());
                    LotRef lot = lotRefs.get(l.lotId());
                    return new ReturnLine(l.id(), new OrderViews.ProductRef(p.id(), p.sku(), p.commercialName(),
                            p.baseUnit()), l.lotId(), lot.lotNumber(), lot.expiryDate(), l.quantityBase(),
                            l.movementId());
                }).toList());
    }

    private SupplierRef supplier(Long id) {
        Supplier s = supplierRows.findById(id).orElseThrow();
        return new SupplierRef(s.id(), s.name(), s.active());
    }

    private Instant startOf(LocalDate day) {
        return day == null ? null : day.atStartOfDay(business.timeZone()).toInstant();
    }
}
