package com.imsvet.ims.inventory.application;

import com.imsvet.ims.catalog.application.ProductLookup;
import com.imsvet.ims.catalog.application.ProductLookup.ProductRef;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.inventory.domain.Lot;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the printed recall buyers list names its lot by (ADR-143): product, lot number, expiry, the recall's reason
 * and time. The caller checks {@code lot.recall} through {@link LotActions#recallReport}.
 */
@Service
@InternalService
public class RecalledLotLabels {

    public record LotLabel(String productName, String sku, String lotNumber, LocalDate expiryDate, String reason,
            Instant recalledAt) {
    }

    private final LotRepository lots;
    private final ProductLookup products;

    public RecalledLotLabels(LotRepository lots, ProductLookup products) {
        this.lots = lots;
        this.products = products;
    }

    @Transactional(readOnly = true)
    public LotLabel of(long lotId) {
        Lot lot = lots.findById(lotId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "Lot not found.", Map.of("id", lotId)));
        ProductRef product = products.find(lot.productId()).orElseThrow();
        return new LotLabel(product.commercialName(), product.sku(), lot.lotNumber(), lot.expiryDate(),
                lot.blockedReason(), lot.blockedAt());
    }
}
