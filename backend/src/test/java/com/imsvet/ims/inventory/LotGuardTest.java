package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.inventory.application.LotGuard;
import com.imsvet.ims.support.TestLots;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * BR-LOT-007 and ADR-062, service level: a lot named for a product must belong to it ({@code LOT_PRODUCT_MISMATCH});
 * the database-level test comes with the first table referencing lots (Phase 4).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class LotGuardTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LotGuard guard;

    @Test
    void aLotOfAnotherProductIsRefused() {
        TestLots lots = new TestLots(jdbc);
        long a = lots.product("GUARD-A", "A");
        long b = lots.product("GUARD-B", "B");
        long lotOfA = lots.lot(a, "LA", LocalDate.of(2027, 1, 31), "5");

        assertThat(guard.lotOfProduct(lotOfA, a).id()).isEqualTo(lotOfA);
        assertThatThrownBy(() -> guard.lotOfProduct(lotOfA, b)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.LOT_PRODUCT_MISMATCH));
        assertThatThrownBy(() -> guard.lotOfProduct(999999, a)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
