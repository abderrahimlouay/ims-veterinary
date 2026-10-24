package com.imsvet.ims.purchasing.application;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.purchasing.domain.Supplier;
import java.util.Map;
import org.springframework.stereotype.Component;

/** A supplier that orders and receipts may use: given, known and active (BR-PURCH-011, ADR-100). */
@Component
class ActiveSuppliers {

    private final SupplierDirectory suppliers;

    ActiveSuppliers(SupplierDirectory suppliers) {
        this.suppliers = suppliers;
    }

    Supplier require(Long supplierId) {
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
}
