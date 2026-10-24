package com.imsvet.ims.purchasing.application;

import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.purchasing.application.OrderViews.OrderLine;
import com.imsvet.ims.purchasing.application.OrderViews.OrderReceipt;
import com.imsvet.ims.purchasing.application.OrderViews.PurchaseOrderDetail;
import com.imsvet.ims.purchasing.application.OrderViews.PurchaseOrderSummary;
import com.imsvet.ims.purchasing.application.OrderViews.SupplierRef;
import com.imsvet.ims.purchasing.application.OrderViews.UnitRef;
import com.imsvet.ims.purchasing.application.OrderViews.UserRef;
import com.imsvet.ims.purchasing.domain.PurchaseOrder;
import com.imsvet.ims.purchasing.domain.PurchaseOrderLine;
import com.imsvet.ims.purchasing.domain.Supplier;
import com.imsvet.ims.purchasing.infrastructure.GoodsReceiptRepository;
import com.imsvet.ims.purchasing.infrastructure.SupplierRepository;
import com.imsvet.ims.users.application.UserNames;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** Builds the purchase-order DTOs, in the caller's transaction. */
@Component
class OrderViewer {

    private final SupplierRepository suppliers;
    private final GoodsReceiptRepository receipts;
    private final ProductLookup products;
    private final UserNames userNames;

    OrderViewer(SupplierRepository suppliers, GoodsReceiptRepository receipts, ProductLookup products,
            UserNames userNames) {
        this.suppliers = suppliers;
        this.receipts = receipts;
        this.products = products;
        this.userNames = userNames;
    }

    PurchaseOrderSummary summary(PurchaseOrder o) {
        return new PurchaseOrderSummary(o.id(), o.number(), supplier(o.supplierId()), o.status().code(), o.orderDate(),
                o.expectedDate(), o.totalTtc());
    }

    PurchaseOrderDetail detail(PurchaseOrder o) {
        List<PurchaseOrderLine> lines = o.lines();
        Map<Long, ProductRef> refs = products.findAll(lines.stream().map(PurchaseOrderLine::productId).toList());
        Map<Long, String> names = userNames.fullNames(Stream.of(o.createdBy(), o.cancelledBy(), o.closedBy())
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return new PurchaseOrderDetail(o.id(), o.number(), supplier(o.supplierId()), o.status().code(), o.orderDate(),
                o.expectedDate(), lines.stream().map(l -> line(l, refs.get(l.productId()))).toList(), o.totalHt(),
                o.totalVat(), o.totalTtc(), user(o.createdBy(), names), o.createdAt(), o.validatedAt(), o.sentAt(),
                o.cancelReason(), user(o.cancelledBy(), names), o.cancelledAt(), o.closedReason(),
                user(o.closedBy(), names), o.closedAt(), receipts.findByPurchaseOrderId(o.id()).stream()
                        .map(r -> new OrderReceipt(r.id(), r.number(), r.receivedAt(), r.status().code())).toList(),
                o.version());
    }

    private OrderLine line(PurchaseOrderLine l, ProductRef p) {
        UnitRef unit = l.unitId() == null ? null : products.unit(p.id(), l.unitId())
                .map(u -> new UnitRef(u.id(), u.name(), u.factorToBase())).orElse(null);
        return new OrderLine(l.id(), new OrderViews.ProductRef(p.id(), p.sku(), p.commercialName(), p.baseUnit()), unit,
                l.quantity(), l.quantityBase(), l.receivedBase(), l.outstandingBase(), l.unitPrice(), l.discountPct(),
                l.vatRate(), l.lineHt(), l.lineVat());
    }

    private SupplierRef supplier(Long id) {
        Supplier s = suppliers.findById(id).orElseThrow();
        return new SupplierRef(s.id(), s.name(), s.active());
    }

    private static UserRef user(Long id, Map<Long, String> names) {
        return id == null ? null : new UserRef(id, names.get(id));
    }
}
