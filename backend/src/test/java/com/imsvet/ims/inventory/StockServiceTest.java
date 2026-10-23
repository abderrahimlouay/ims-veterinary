package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.inventory.application.StockService;
import com.imsvet.ims.inventory.application.StockService.Movement;
import com.imsvet.ims.inventory.domain.MovementType;
import com.imsvet.ims.support.StockAssertions;
import com.imsvet.ims.support.TestActor;
import com.imsvet.ims.support.TestLots;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code StockService}, the only writer of stock (BR-STOCK-002, ADR-088): it locks the lots, checks every movement
 * (product, sign, reason, balance ≥ 0) before applying any, inserts attributable movements (BR-STOCK-004) and moves
 * the balances by the same amounts. Ledger = balance after each test. Running outside a transaction is refused
 * ({@code StockAtomicityTest}, which may commit).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class StockServiceTest {

    private static final LocalDate EXPIRY = LocalDate.of(2027, 5, 31);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StockService stock;

    @Autowired
    private TestClock clock;

    private TestLots lots;
    private long product;
    private long lotA;
    private long lotB;
    private long operator;

    @BeforeEach
    void stock() {
        clock.reset();
        lots = new TestLots(jdbc);
        product = lots.product("STOCK-SVC", "Amoxival");
        lotA = lots.lot(product, "L-A", EXPIRY, "10");
        lotB = lots.lot(product, "L-B", EXPIRY.plusMonths(1), "4");
        operator = lots.fixtureUser();
        TestActor.signIn(operator);
    }

    @AfterEach
    void signOut() {
        TestActor.signOut();
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lotA, lotB));
    }

    @Test
    void aMovementIsRecordedAndMovesTheBalanceBySameAmount() {
        List<Long> ids = stock.post(List.of(new Movement(lotA, product, MovementType.ADJUSTMENT_BREAKAGE,
                new BigDecimal("-2.5"), "Broken vial", "adjustment", null)));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT movement_type, quantity, reason, user_id, reference_type, created_at
                FROM stock_movements WHERE id = ?""", ids.getFirst());
        assertThat(row).containsEntry("movement_type", "adjustment_breakage").containsEntry("reason", "Broken vial")
                .containsEntry("user_id", operator).containsEntry("reference_type", "adjustment");
        assertThat(row.get("quantity").toString()).isEqualTo("-2.500");
        assertThat(row.get("created_at").toString()).startsWith("2026-10-20T08:00");
        assertThat(balance(lotA)).isEqualTo("7.500");
    }

    @Test
    void severalLotsAreChangedTogether() {
        stock.post(List.of(new Movement(lotB, product, MovementType.ADJUSTMENT_INVENTORY, new BigDecimal("1"), "Count",
                "inventory", 9L), new Movement(lotA, product, MovementType.ADJUSTMENT_INVENTORY, new BigDecimal("-3"),
                "Count", "inventory", 9L)));

        assertThat(balance(lotA)).isEqualTo("7.000");
        assertThat(balance(lotB)).isEqualTo("5.000");
    }

    @Test
    void stockCannotGoNegativeAndNothingIsApplied() {
        assertThatThrownBy(() -> stock.post(List.of(
                new Movement(lotA, product, MovementType.ADJUSTMENT_THEFT, new BigDecimal("-1"), "Theft", null, null),
                new Movement(lotB, product, MovementType.ADJUSTMENT_THEFT, new BigDecimal("-4.001"), "Theft", null, null))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.STOCK_INSUFFICIENT);
                    assertThat(e.context()).containsEntry("lotId", lotB).containsEntry("available", "4.000");
                });

        assertThat(balance(lotA)).isEqualTo("10.000");
        assertThat(movementCount()).isEqualTo(2);
    }

    @Test
    void aLotOfAnotherProductIsRefused() {
        long other = lots.product("STOCK-OTHER", "Other");

        assertThatThrownBy(() -> stock.post(List.of(new Movement(lotA, other, MovementType.ADJUSTMENT_EXPIRY,
                new BigDecimal("-1"), "Expired", null, null))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.LOT_PRODUCT_MISMATCH));
    }

    @Test
    void aWrongSignOrAMissingReasonIsRefused() {
        assertThatThrownBy(() -> stock.post(List.of(new Movement(lotA, product, MovementType.OPENING_STOCK,
                new BigDecimal("-1"), null, null, null)))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> stock.post(List.of(new Movement(lotA, product, MovementType.ADJUSTMENT_THEFT,
                new BigDecimal("-1"), "  ", null, null))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ADJUSTMENT_REASON_REQUIRED));
        assertThat(movementCount()).isEqualTo(2);
    }

    @Test
    void anUnknownLotIsNotFound() {
        assertThatThrownBy(() -> stock.post(List.of(new Movement(999_999, product, MovementType.ADJUSTMENT_EXPIRY,
                new BigDecimal("-1"), "Expired", null, null))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void movementsNeedAnOperator() {
        TestActor.signOut();

        assertThatThrownBy(() -> stock.post(List.of(new Movement(lotA, product, MovementType.ADJUSTMENT_EXPIRY,
                new BigDecimal("-1"), "Expired", null, null)))).isInstanceOf(IllegalStateException.class);
    }

    private String balance(long lot) {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", String.class, lot);
    }

    private int movementCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE lot_id IN (?, ?)", Integer.class, lotA, lotB);
    }
}
