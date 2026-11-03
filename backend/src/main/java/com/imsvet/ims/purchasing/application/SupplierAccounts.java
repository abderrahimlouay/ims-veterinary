package com.imsvet.ims.purchasing.application;

import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.purchasing.domain.GoodsReceipt;
import com.imsvet.ims.purchasing.domain.GoodsReceiptLine;
import com.imsvet.ims.purchasing.domain.ReceiptStatus;
import com.imsvet.ims.purchasing.domain.Supplier;
import com.imsvet.ims.purchasing.infrastructure.GoodsReceiptRepository;
import com.imsvet.ims.purchasing.infrastructure.SupplierLocks;
import com.imsvet.ims.purchasing.infrastructure.SupplierRepository;
import com.imsvet.ims.purchasing.infrastructure.SupplierReturnRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read port through which {@code finance} sees suppliers, their receipts and their returns (ADR-132, ADR-133), and
 * locks a supplier row to serialise its settlements. The caller checks its permission.
 */
@Service
@InternalService
public class SupplierAccounts {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public record SupplierRef(long id, String name, boolean active, int paymentTermsDays) {
    }

    /**
     * {@code valueTtc}: Σ per line of quantity × unit cost × (1 + the product's VAT rate), each line rounded to 2
     * decimals HALF_UP (ADR-093); what a supplier invoice for the receipt is compared with, as a warning only.
     */
    public record ReceiptRef(long id, String number, long supplierId, boolean confirmed, BigDecimal valueTtc) {
    }

    public record ReturnRef(long id, String number, long supplierId) {
    }

    private final SupplierRepository suppliers;
    private final SupplierLocks locks;
    private final GoodsReceiptRepository receipts;
    private final SupplierReturnRepository returns;
    private final ProductLookup products;

    public SupplierAccounts(SupplierRepository suppliers, SupplierLocks locks, GoodsReceiptRepository receipts,
            SupplierReturnRepository returns, ProductLookup products) {
        this.suppliers = suppliers;
        this.locks = locks;
        this.receipts = receipts;
        this.returns = returns;
        this.products = products;
    }

    @Transactional(readOnly = true)
    public Optional<SupplierRef> supplier(long id) {
        return suppliers.findById(id).map(SupplierAccounts::ref);
    }

    /** Locks the supplier row; empty for an unknown supplier. */
    @Transactional
    public Optional<SupplierRef> lock(long id) {
        return locks.lock(id).map(SupplierAccounts::ref);
    }

    @Transactional(readOnly = true)
    public Optional<ReceiptRef> receipt(long id) {
        return receipts.findById(id).map(this::receiptRef);
    }

    @Transactional(readOnly = true)
    public Optional<ReturnRef> supplierReturn(long id) {
        return returns.findById(id).map(r -> new ReturnRef(r.id(), r.number(), r.supplierId()));
    }

    private ReceiptRef receiptRef(GoodsReceipt receipt) {
        Map<Long, ProductRef> lines = products.findAll(receipt.lines().stream().map(GoodsReceiptLine::productId)
                .distinct().toList());
        BigDecimal value = BigDecimal.ZERO;
        for (GoodsReceiptLine line : receipt.lines()) {
            BigDecimal vat = lines.get(line.productId()).vatRate();
            value = value.add(line.quantityBase().multiply(line.unitCost())
                    .multiply(BigDecimal.ONE.add(vat.divide(HUNDRED))).setScale(2, RoundingMode.HALF_UP));
        }
        return new ReceiptRef(receipt.id(), receipt.number(), receipt.supplierId(),
                receipt.status() == ReceiptStatus.CONFIRMED, value);
    }

    private static SupplierRef ref(Supplier supplier) {
        return new SupplierRef(supplier.id(), supplier.name(), supplier.active(), supplier.paymentTermsDays());
    }
}
