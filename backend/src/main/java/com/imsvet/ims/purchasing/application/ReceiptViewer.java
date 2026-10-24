package com.imsvet.ims.purchasing.application;

import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.inventory.application.LotReferences;
import com.imsvet.ims.inventory.application.LotReferences.LotRef;
import com.imsvet.ims.purchasing.application.OrderViews.SupplierRef;
import com.imsvet.ims.purchasing.application.OrderViews.UserRef;
import com.imsvet.ims.purchasing.application.ReceiptViews.ReceiptDetail;
import com.imsvet.ims.purchasing.application.ReceiptViews.ReceiptLine;
import com.imsvet.ims.purchasing.application.ReceiptViews.ReceiptOrder;
import com.imsvet.ims.purchasing.application.ReceiptViews.ReceiptSummary;
import com.imsvet.ims.purchasing.domain.GoodsReceipt;
import com.imsvet.ims.purchasing.domain.GoodsReceiptLine;
import com.imsvet.ims.purchasing.domain.Supplier;
import com.imsvet.ims.purchasing.infrastructure.PurchaseOrderRepository;
import com.imsvet.ims.purchasing.infrastructure.SupplierRepository;
import com.imsvet.ims.users.application.UserNames;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** Builds the goods-receipt DTOs, in the caller's transaction. */
@Component
class ReceiptViewer {

    private final SupplierRepository suppliers;
    private final PurchaseOrderRepository orders;
    private final ProductLookup products;
    private final LotReferences lots;
    private final UserNames userNames;

    ReceiptViewer(SupplierRepository suppliers, PurchaseOrderRepository orders, ProductLookup products,
            LotReferences lots, UserNames userNames) {
        this.suppliers = suppliers;
        this.orders = orders;
        this.products = products;
        this.lots = lots;
        this.userNames = userNames;
    }

    ReceiptSummary summary(GoodsReceipt r) {
        return new ReceiptSummary(r.id(), r.number(), supplier(r.supplierId()), order(r.purchaseOrderId()),
                r.receivedAt(), r.status().code());
    }

    ReceiptDetail detail(GoodsReceipt r) {
        List<GoodsReceiptLine> lines = r.lines();
        Map<Long, ProductRef> refs = products.findAll(
                lines.stream().map(GoodsReceiptLine::productId).collect(Collectors.toSet()));
        Map<Long, LotRef> lotRefs = lots.find(lines.stream().map(GoodsReceiptLine::lotId).toList());
        Map<Long, String> names = userNames.fullNames(Stream.of(r.receivedBy(), r.cancelledBy())
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return new ReceiptDetail(r.id(), r.number(), r.status().code(), supplier(r.supplierId()),
                order(r.purchaseOrderId()), r.supplierDocRef(), r.notes(), r.receivedAt(), user(r.receivedBy(), names),
                lines.stream().map(l -> line(l, refs.get(l.productId()), lotRefs.get(l.lotId()))).toList(),
                r.cancelReason(), user(r.cancelledBy(), names), r.cancelledAt());
    }

    private static ReceiptLine line(GoodsReceiptLine l, ProductRef p, LotRef lot) {
        return new ReceiptLine(l.id(), l.poLineId(),
                new OrderViews.ProductRef(p.id(), p.sku(), p.commercialName(), p.baseUnit()), l.lotId(),
                lot.lotNumber(), lot.expiryDate(), l.quantityBase(), l.unitCost(), l.conforming(), l.lotBlocked(),
                l.movementId());
    }

    private ReceiptOrder order(Long id) {
        return id == null ? null : orders.findById(id)
                .map(o -> new ReceiptOrder(o.id(), o.number(), o.status().code())).orElseThrow();
    }

    private SupplierRef supplier(Long id) {
        Supplier s = suppliers.findById(id).orElseThrow();
        return new SupplierRef(s.id(), s.name(), s.active());
    }

    private static UserRef user(Long id, Map<Long, String> names) {
        return id == null ? null : new UserRef(id, names.get(id));
    }
}
