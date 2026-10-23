package com.imsvet.ims.inventory.application;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import java.sql.SQLException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * BR-STOCK-001: if the balance CHECK of {@code lots} is ever reached (MySQL error 3819) although the service checks
 * first, the client gets {@code STOCK_INSUFFICIENT} rather than an internal error (backend.md §8).
 */
public final class StockConstraintTranslator {

    private static final int CHECK_VIOLATED = 3819;

    private StockConstraintTranslator() {
    }

    /** {@code null} when the violation is not the balance CHECK. */
    public static BusinessException translate(DataIntegrityViolationException violation) {
        if (violation.getMostSpecificCause() instanceof SQLException sql && sql.getErrorCode() == CHECK_VIOLATED
                && String.valueOf(sql.getMessage()).contains("ck_lots_quantity_on_hand_non_negative")) {
            return new BusinessException(ErrorCode.STOCK_INSUFFICIENT, "Not enough stock on the lot.");
        }
        return null;
    }
}
