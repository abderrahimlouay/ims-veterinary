package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.inventory.application.StockConstraintTranslator;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** BR-STOCK-001: if the balance CHECK is ever reached (error 3819), the client gets STOCK_INSUFFICIENT, not a 500. */
class StockConstraintTranslatorTest {

    @Test
    void theBalanceCheckBecomesStockInsufficient() {
        DataIntegrityViolationException violation = new DataIntegrityViolationException("x", new SQLException(
                "Check constraint 'ck_lots_quantity_on_hand_non_negative' is violated.", "HY000", 3819));

        BusinessException translated = StockConstraintTranslator.translate(violation);

        assertThat(translated).isNotNull();
        assertThat(translated.code()).isEqualTo(ErrorCode.STOCK_INSUFFICIENT);
    }

    @Test
    void otherViolationsAreLeftAlone() {
        DataIntegrityViolationException other = new DataIntegrityViolationException("x",
                new SQLException("Duplicate entry", "23000", 1062));

        assertThat(StockConstraintTranslator.translate(other)).isNull();
    }
}
