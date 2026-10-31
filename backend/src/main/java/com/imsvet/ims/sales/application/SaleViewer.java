package com.imsvet.ims.sales.application;

import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.catalog.application.ProductWithdrawals;
import com.imsvet.ims.catalog.application.ProductWithdrawals.Period;
import com.imsvet.ims.inventory.application.SaleLots;
import com.imsvet.ims.inventory.application.SaleLots.LotInfo;
import com.imsvet.ims.sales.application.SalesViews.CustomerRef;
import com.imsvet.ims.sales.application.SalesViews.SaleDetail;
import com.imsvet.ims.sales.application.SalesViews.SaleLine;
import com.imsvet.ims.sales.application.SalesViews.SalePayment;
import com.imsvet.ims.sales.application.SalesViews.SaleWarning;
import com.imsvet.ims.sales.domain.SalesDocStatus;
import com.imsvet.ims.sales.domain.SalesDocument;
import com.imsvet.ims.sales.domain.SalesDocumentLine;
import com.imsvet.ims.sales.domain.WithdrawalWarnings;
import com.imsvet.ims.sales.infrastructure.CustomerRepository;
import com.imsvet.ims.sales.infrastructure.SalesDocumentLineRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The detail of a sales document with its lines and warnings (api.md §5.5), which never block. Near expiry and low
 * stock concern a draft only; the withdrawal periods (ADR-121) stay on every document.
 */
@Component
class SaleViewer {

    private final SalesDocumentLineRepository lines;
    private final CustomerRepository customers;
    private final ProductLookup products;
    private final ProductWithdrawals withdrawals;
    private final SaleLots lots;
    private final PaymentRecorder payments;

    SaleViewer(SalesDocumentLineRepository lines, CustomerRepository customers, ProductLookup products,
            ProductWithdrawals withdrawals, SaleLots lots, PaymentRecorder payments) {
        this.lines = lines;
        this.customers = customers;
        this.products = products;
        this.withdrawals = withdrawals;
        this.lots = lots;
        this.payments = payments;
    }

    SaleDetail detail(SalesDocument document) {
        CustomerRef customer = document.customerId() == null ? null : customers.findById(document.customerId())
                .map(c -> new CustomerRef(c.id(), c.name())).orElseThrow();
        List<SalesDocumentLine> found = lines.findByDocumentIdOrderByIdAsc(document.id());
        boolean draft = document.status() == SalesDocStatus.DRAFT;
        Map<Long, BigDecimal> heldPerProduct = new HashMap<>();
        found.forEach(l -> heldPerProduct.merge(l.productId(), l.quantityBase(), BigDecimal::add));
        return SalesViews.detail(document, customer, found.stream().map(l -> {
            ProductRef product = products.find(l.productId()).orElseThrow();
            LotInfo lot = l.lotId() == null ? null : lots.info(l.lotId());
            String unit = l.unitId() == null ? product.baseUnit()
                    : products.unit(l.productId(), l.unitId()).orElseThrow().name();
            return SalesViews.line(l, product.commercialName(), unit, lot,
                    warnings(l, product, lot, draft, heldPerProduct.get(l.productId())));
        }).toList(), payments.inForceOn(document.id()).stream()
                .map(p -> new SalePayment(p.id(), p.method(), p.amount(), p.reference())).toList());
    }

    /** The withdrawal warnings of all lines, once per product and species. */
    List<SaleWarning> withdrawalWarnings(SalesDocument document) {
        return lines.findByDocumentIdOrderByIdAsc(document.id()).stream().flatMap(l -> withdrawal(l).stream())
                .distinct().toList();
    }

    private List<SaleWarning> warnings(SalesDocumentLine line, ProductRef product, LotInfo lot, boolean draft,
            BigDecimal held) {
        List<SaleWarning> warnings = new ArrayList<>();
        if (draft && lot != null && lot.alertLevel() != null) {
            warnings.add(SaleWarning.nearExpiry(line.productId(), lot.expiryDate(), lot.alertLevel()));
        }
        warnings.addAll(withdrawal(line));
        if (draft && lot != null && product.minStock().signum() > 0
                && lots.sellable(line.productId()).subtract(held).compareTo(product.minStock()) <= 0) {
            warnings.add(SaleWarning.lowStock(line.productId()));
        }
        return warnings;
    }

    private List<SaleWarning> withdrawal(SalesDocumentLine line) {
        return WithdrawalWarnings.select(withdrawals.of(line.productId()), line.targetSpeciesId(), Period::speciesId,
                        p -> p.meatDays() != null || p.milkDays() != null || p.eggsDays() != null).stream()
                .map(p -> SaleWarning.withdrawal(line.productId(), p)).toList();
    }
}
