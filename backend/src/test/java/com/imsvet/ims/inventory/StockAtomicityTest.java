package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.inventory.application.StockService;
import com.imsvet.ims.inventory.application.StockService.Movement;
import com.imsvet.ims.inventory.domain.MovementType;
import com.imsvet.ims.support.StockAssertions;
import com.imsvet.ims.support.TestActor;
import com.imsvet.ims.support.TestLots;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fault injection (BR-STOCK-002, BR-PURCH-007 pattern): a failure after the movement was written, later in the same
 * use case, leaves no movement and no balance change once the transaction is over — checked on committed data.
 * {@code StockService} also refuses to run outside a transaction (propagation MANDATORY).
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class StockAtomicityTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StockService stock;

    @Autowired
    private TransactionTemplate transactions;

    private long product;
    private long lot;

    @BeforeEach
    void lot() {
        TestLots lots = new TestLots(jdbc);
        String key = UUID.randomUUID().toString().substring(0, 8);
        product = lots.product("ATOM-" + key, "Atomic");
        lot = lots.lot(product, "L-" + key, LocalDate.of(2027, 5, 31), "10");
        TestActor.signIn(lots.fixtureUser());
    }

    @AfterEach
    void signOut() {
        TestActor.signOut();
    }

    @Test
    void aFailureAfterTheMovementRollsEverythingBack() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            stock.post(List.of(new Movement(lot, product, MovementType.ADJUSTMENT_BREAKAGE, new BigDecimal("-3"),
                    "Broken", null, null)));
            throw new IllegalStateException("audit_log unavailable");
        })).hasMessage("audit_log unavailable");

        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", String.class, lot))
                .isEqualTo("10.000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE lot_id = ?", Integer.class, lot))
                .isEqualTo(1);
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
    }

    @Test
    void stockIsOnlyWrittenInsideTheCallersTransaction() {
        assertThatThrownBy(() -> stock.post(List.of(new Movement(lot, product, MovementType.ADJUSTMENT_EXPIRY,
                new BigDecimal("-1"), "Expired", null, null)))).isInstanceOf(IllegalTransactionStateException.class);

        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", String.class, lot))
                .isEqualTo("10.000");
    }
}
