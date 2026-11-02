package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.sales.domain.RestockRefusal;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** BR-RET-002, ADR-129 (U): when returned goods may go back into their lot, and why not. */
class RestockRefusalTest {

    @ParameterizedTest
    @CsvSource(nullValues = "none", value = {
            "true,  false, active,   false, none",
            "true,  false, blocked,  false, none",
            "true,  false, recalled, false, none",
            "false, false, active,   false, not_sealed",
            "false, true,  active,   true,  not_sealed",
            "true,  true,  active,   false, lot_expired",
            "true,  true,  recalled, false, lot_expired",
            "true,  false, active,   true,  product_restricted",
            "true,  false, blocked,  true,  product_restricted",
            "true,  false, recalled, true,  none" })
    void sealedGoodsGoBackIntoAnUnexpiredLotAndARestrictedProductOnlyIntoARecalledOne(boolean sealed,
            boolean expired, String status, boolean restricted, String refusal) {
        assertThat(RestockRefusal.of(sealed, expired, status, restricted).map(RestockRefusal::code))
                .isEqualTo(Optional.ofNullable(refusal));
    }
}
