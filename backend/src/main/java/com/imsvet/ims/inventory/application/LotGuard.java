package com.imsvet.ims.inventory.application;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.inventory.domain.Lot;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BR-LOT-007 and ADR-062: every use case that receives a lot for a product checks it here and answers
 * {@code LOT_PRODUCT_MISMATCH} before the composite foreign keys of later tables would refuse it. Used by the
 * stock-moving services from Phase 4; callers check their own permission.
 */
@Service
@InternalService
public class LotGuard {

    private final LotRepository lots;

    public LotGuard(LotRepository lots) {
        this.lots = lots;
    }

    @Transactional(readOnly = true)
    public Lot lotOfProduct(long lotId, long productId) {
        Lot lot = lots.findById(lotId).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Lot not found.", Map.of("id", lotId)));
        if (lot.productId() != productId) {
            throw new BusinessException(ErrorCode.LOT_PRODUCT_MISMATCH, "This lot belongs to another product.",
                    Map.of("lotId", lotId, "productId", productId));
        }
        return lot;
    }
}
