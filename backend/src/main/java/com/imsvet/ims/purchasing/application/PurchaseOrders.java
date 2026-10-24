package com.imsvet.ims.purchasing.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.numbering.application.NumberingService;
import com.imsvet.ims.numbering.domain.DocumentKey;
import com.imsvet.ims.purchasing.application.OrderViews.PurchaseOrderDetail;
import com.imsvet.ims.purchasing.application.OrderViews.PurchaseOrderSummary;
import com.imsvet.ims.purchasing.domain.OrderStatus;
import com.imsvet.ims.purchasing.domain.PurchaseOrder;
import com.imsvet.ims.purchasing.domain.PurchaseOrder.IllegalOrderTransitionException;
import com.imsvet.ims.purchasing.domain.PurchaseOrder.LineInput;
import com.imsvet.ims.purchasing.domain.PurchaseOrderLine;
import com.imsvet.ims.purchasing.domain.Supplier;
import com.imsvet.ims.purchasing.infrastructure.PurchaseOrderRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purchase orders (FR-PURCH-003/004, W1a). Creating validates the supplier (active, BR-PURCH-011) and the lines
 * first, then takes the number (ADR-097: a refused creation consumes none) and saves. Only drafts change
 * (BR-PURCH-003, optimistic lock, ADR-027). Transitions follow the status table (BR-PURCH-001); validating checks
 * again that the supplier is active and the products are not archived; cancel and forced close need a reason and
 * are audited {@code PO_CANCEL} / {@code PO_CLOSE} in the same transaction (ADR-096, ADR-105).
 */
@Service
public class PurchaseOrders {

    public record OrderInput(Long supplierId, LocalDate expectedDate, List<RequestedLine> lines) {
    }

    /** A requested line; amounts are never part of it (BR-PURCH-002). */
    public record RequestedLine(Long productId, Long unitId, BigDecimal quantity, BigDecimal unitPrice,
            BigDecimal discountPct, BigDecimal vatRate) {
    }

    private final PurchaseOrderRepository orders;
    private final SupplierDirectory suppliers;
    private final OrderLineValidator validator;
    private final OrderViewer viewer;
    private final ProductLookup products;
    private final NumberingService numbering;
    private final AuditRecorder audit;
    private final ActorProvider actors;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public PurchaseOrders(PurchaseOrderRepository orders, SupplierDirectory suppliers, OrderLineValidator validator,
            OrderViewer viewer, ProductLookup products, NumberingService numbering, AuditRecorder audit,
            ActorProvider actors, BusinessCalendar calendar, Clock clock) {
        this.orders = orders;
        this.suppliers = suppliers;
        this.validator = validator;
        this.viewer = viewer;
        this.products = products;
        this.numbering = numbering;
        this.audit = audit;
        this.actors = actors;
        this.calendar = calendar;
        this.clock = clock;
    }

    /** {@code status} {@code from}/{@code to} optional; newest first. */
    @PreAuthorize("hasAuthority('purchase.read')")
    @Transactional(readOnly = true)
    public PageResponse<PurchaseOrderSummary> search(String status, Long supplierId, LocalDate from, LocalDate to,
            int page, int size) {
        OrderStatus wanted = status == null || status.isBlank() ? null : OrderStatus.fromCode(status)
                .orElseThrow(() -> OrderLineValidator.invalid("status", "UNKNOWN", "Unknown status."));
        Page<PurchaseOrder> found = orders.search(wanted, supplierId, from, to,
                PageRequests.of(page, size, Sort.unsorted()));
        return PageResponse.of(found, viewer::summary);
    }

    @PreAuthorize("hasAuthority('purchase.read')")
    @Transactional(readOnly = true)
    public PurchaseOrderDetail get(long id) {
        return viewer.detail(find(id));
    }

    @PreAuthorize("hasAuthority('purchase.create')")
    @Transactional
    public PurchaseOrderDetail create(OrderInput in) {
        Supplier supplier = activeSupplier(in.supplierId());
        List<LineInput> lines = validator.validate(in.lines());
        PurchaseOrder order = new PurchaseOrder(numbering.next(DocumentKey.PO), supplier.id(), calendar.today(),
                in.expectedDate(), actors.current().userId(), clock.instant());
        order.addLines(lines);
        return viewer.detail(orders.saveAndFlush(order));
    }

    /** A full replacement of supplier, expected date and lines, while draft and with the current version. */
    @PreAuthorize("hasAuthority('purchase.create')")
    @Transactional
    public PurchaseOrderDetail update(long id, OrderInput in, long version) {
        PurchaseOrder order = find(id);
        if (order.status() != OrderStatus.DRAFT) {
            throw new BusinessException(ErrorCode.DOCUMENT_IMMUTABLE, "Only a draft order can be edited.",
                    Map.of("status", order.status().code()));
        }
        if (order.version() != version) {
            throw new ObjectOptimisticLockingFailureException(PurchaseOrder.class, id);
        }
        Supplier supplier = activeSupplier(in.supplierId());
        List<LineInput> lines = validator.validate(in.lines());
        order.clearLines(supplier.id(), in.expectedDate(), clock.instant());
        orders.flush();
        order.addLines(lines);
        orders.flush();
        return viewer.detail(order);
    }

    @PreAuthorize("hasAuthority('purchase.validate')")
    @Transactional
    public PurchaseOrderDetail validate(long id) {
        PurchaseOrder order = find(id);
        if (order.status() == OrderStatus.DRAFT) {
            activeSupplier(order.supplierId());
            for (PurchaseOrderLine line : order.lines()) {
                if (products.find(line.productId()).orElseThrow().archived()) {
                    throw new BusinessException(ErrorCode.PRODUCT_ARCHIVED, "This product is archived.",
                            Map.of("productId", line.productId()));
                }
            }
        }
        return transition(order, OrderStatus.Action.VALIDATE, null);
    }

    @PreAuthorize("hasAuthority('purchase.validate')")
    @Transactional
    public PurchaseOrderDetail send(long id) {
        return transition(find(id), OrderStatus.Action.SEND, null);
    }

    @PreAuthorize("hasAuthority('purchase.validate')")
    @Transactional
    public PurchaseOrderDetail cancel(long id, String reason) {
        return transition(find(id), OrderStatus.Action.CANCEL, required(reason));
    }

    /** Forced close of a partially received order (ADR-096). */
    @PreAuthorize("hasAuthority('purchase.validate')")
    @Transactional
    public PurchaseOrderDetail close(long id, String reason) {
        return transition(find(id), OrderStatus.Action.CLOSE, required(reason));
    }

    private PurchaseOrderDetail transition(PurchaseOrder order, OrderStatus.Action action, String reason) {
        OrderStatus before = order.status();
        try {
            order.apply(action, reason, actors.current().userId(), clock.instant());
        } catch (IllegalOrderTransitionException e) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, e.getMessage(),
                    Map.of("status", before.code(), "action", action.name().toLowerCase(Locale.ROOT)));
        }
        if (action == OrderStatus.Action.CANCEL || action == OrderStatus.Action.CLOSE) {
            Map<String, Object> newValues = new LinkedHashMap<>();
            newValues.put("status", order.status().code());
            newValues.put("reason", reason);
            newValues.put("number", order.number());
            audit.record(action == OrderStatus.Action.CANCEL ? AuditAction.PO_CANCEL : AuditAction.PO_CLOSE,
                    "purchase_orders", order.id(), Map.of("status", before.code()), newValues);
        }
        return viewer.detail(orders.saveAndFlush(order));
    }

    private Supplier activeSupplier(Long supplierId) {
        if (supplierId == null) {
            throw OrderLineValidator.invalid("supplierId", "REQUIRED", "The supplier is required.");
        }
        Supplier supplier = suppliers.findOptional(supplierId)
                .orElseThrow(() -> OrderLineValidator.invalid("supplierId", "UNKNOWN", "Unknown supplier."));
        if (!supplier.active()) {
            throw new BusinessException(ErrorCode.SUPPLIER_INACTIVE, "This supplier is inactive.",
                    Map.of("supplierId", supplierId));
        }
        return supplier;
    }

    private static String required(String reason) {
        if (reason == null || reason.isBlank()) {
            throw OrderLineValidator.invalid("reason", "REQUIRED", "A reason is required.");
        }
        return reason.trim();
    }

    private PurchaseOrder find(long id) {
        return orders.findById(id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Purchase order not found.", Map.of("id", id)));
    }
}
