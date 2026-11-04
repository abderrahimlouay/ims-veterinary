package com.imsvet.ims.purchasing.application;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.purchasing.application.OrderViews.PurchaseOrderDetail;
import com.imsvet.ims.purchasing.domain.PurchaseOrder;
import com.imsvet.ims.purchasing.domain.Supplier;
import com.imsvet.ims.purchasing.infrastructure.PurchaseOrderRepository;
import com.imsvet.ims.purchasing.infrastructure.SupplierRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read port the {@code printing} module prints a purchase order from (ADR-143): the detail as the API shows it,
 * the supplier's printed identity and the moment the order was issued (its validation). The caller checks
 * {@code purchase.read}.
 */
@Service
@InternalService
public class PrintablePurchaseOrders {

    public record PrintedSupplier(String name, String address, String phone, String email, String taxId) {
    }

    /** {@code issuedAt}: the validation, {@code null} for a draft. */
    public record OrderPrint(PurchaseOrderDetail detail, PrintedSupplier supplier, Instant issuedAt) {
    }

    private final PurchaseOrderRepository orders;
    private final SupplierRepository suppliers;
    private final OrderViewer viewer;

    PrintablePurchaseOrders(PurchaseOrderRepository orders, SupplierRepository suppliers, OrderViewer viewer) {
        this.orders = orders;
        this.suppliers = suppliers;
        this.viewer = viewer;
    }

    @Transactional(readOnly = true)
    public OrderPrint of(long id) {
        PurchaseOrder order = orders.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "Purchase order not found.", Map.of("id", id)));
        Supplier s = suppliers.findById(order.supplierId()).orElseThrow();
        return new OrderPrint(viewer.detail(order), new PrintedSupplier(s.name(), s.address(), s.phone(), s.email(),
                s.taxId()), order.validatedAt());
    }
}
