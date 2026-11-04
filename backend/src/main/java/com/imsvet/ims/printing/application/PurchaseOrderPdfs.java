package com.imsvet.ims.printing.application;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.time.BusinessSettings;
import com.imsvet.ims.printing.application.PdfRenderer.Paper;
import com.imsvet.ims.printing.application.SalePdfs.Pdf;
import com.imsvet.ims.purchasing.application.OrderViews.OrderLine;
import com.imsvet.ims.purchasing.application.OrderViews.PurchaseOrderDetail;
import com.imsvet.ims.purchasing.application.PrintablePurchaseOrders;
import com.imsvet.ims.purchasing.application.PrintablePurchaseOrders.OrderPrint;
import com.imsvet.ims.purchasing.application.PrintablePurchaseOrders.PrintedSupplier;
import com.imsvet.ims.settings.application.CompanyIdentity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A purchase order as an A4 PDF (api.md `/api/purchase-orders/{id}/pdf`, ADR-143): printed once validated, with the
 * company identity in force at its validation and the supplier's details; a draft refused; "ANNULÉ" on a cancelled
 * order.
 */
@Service
public class PurchaseOrderPdfs {

    private final PrintablePurchaseOrders orders;
    private final CompanyIdentity identity;
    private final PdfRenderer renderer;
    private final BusinessSettings business;

    public PurchaseOrderPdfs(PrintablePurchaseOrders orders, CompanyIdentity identity, PdfRenderer renderer,
            BusinessSettings business) {
        this.orders = orders;
        this.identity = identity;
        this.renderer = renderer;
        this.business = business;
    }

    @PreAuthorize("hasAuthority('purchase.read')")
    @Transactional(readOnly = true)
    public Pdf render(long id) {
        OrderPrint print = orders.of(id);
        PurchaseOrderDetail d = print.detail();
        if (print.issuedAt() == null) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "A draft order is not printed.",
                    Map.of("status", d.status()));
        }
        String currency = PrintFormats.symbol(business.currency());
        Map<String, Object> model = new HashMap<>();
        model.put("company", PrintFormats.company(identity.at(print.issuedAt()).orElse(null)));
        Map<String, Object> doc = new HashMap<>();
        doc.put("number", d.number());
        doc.put("orderDate", PrintFormats.date(d.orderDate()));
        doc.put("expectedDate", PrintFormats.date(d.expectedDate()));
        doc.put("cancelled", "cancelled".equals(d.status()));
        doc.put("cancelReason", d.cancelReason());
        model.put("doc", doc);
        model.put("supplier", supplier(print.supplier()));
        List<Map<String, Object>> lines = new ArrayList<>();
        for (OrderLine l : d.lines()) {
            Map<String, Object> line = new HashMap<>();
            line.put("sku", l.product().sku());
            line.put("product", l.product().commercialName());
            line.put("quantity", PrintFormats.quantity(l.quantity()) + " "
                    + (l.unit() == null ? l.product().baseUnit() : l.unit().name()));
            line.put("unitPrice", PrintFormats.money(l.unitPrice()));
            line.put("discount", l.discountPct() == null || l.discountPct().signum() == 0 ? "—"
                    : PrintFormats.percent(l.discountPct()));
            line.put("vatRate", PrintFormats.percent(l.vatRate()));
            line.put("ht", PrintFormats.money(l.lineHt()));
            lines.add(line);
        }
        model.put("lines", lines);
        model.put("totals", Map.of("ht", PrintFormats.money(d.totalHt(), currency), "vat",
                PrintFormats.money(d.totalVat(), currency), "ttc", PrintFormats.money(d.totalTtc(), currency)));
        return new Pdf(renderer.render("purchase-order-a4", model, Paper.A4), d.number() + ".pdf");
    }

    private static Map<String, Object> supplier(PrintedSupplier s) {
        List<String> lines = new ArrayList<>();
        PrintFormats.add(lines, s.address());
        PrintFormats.add(lines, PrintFormats.joined(" · ", PrintFormats.labelled("Tél.", s.phone()), s.email()));
        PrintFormats.add(lines, PrintFormats.labelled("NIF", s.taxId()));
        return Map.of("name", s.name(), "lines", lines);
    }
}
