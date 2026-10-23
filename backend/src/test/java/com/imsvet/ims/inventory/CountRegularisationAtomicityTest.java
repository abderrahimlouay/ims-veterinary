package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.audit.infrastructure.AuditEntryRepository;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.inventory.application.CountQueries;
import com.imsvet.ims.inventory.application.CountRegularisation;
import com.imsvet.ims.inventory.application.InventoryCounts;
import com.imsvet.ims.inventory.application.InventoryCounts.AddInput;
import com.imsvet.ims.inventory.application.InventoryCounts.OpenInput;
import com.imsvet.ims.inventory.application.LotIdentities;
import com.imsvet.ims.inventory.application.StockService;
import com.imsvet.ims.inventory.infrastructure.CountLineRepository;
import com.imsvet.ims.inventory.infrastructure.InventoryCountRepository;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import com.imsvet.ims.inventory.infrastructure.StockMovementRepository;
import com.imsvet.ims.support.StockAssertions;
import com.imsvet.ims.support.TestActor;
import com.imsvet.ims.support.TestLots;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fault injection on regularisation (BR-INV-002, BR-AUDIT-003, ADR-075), on committed data: a failure while posting
 * the second line, or a failed audit write, rolls back the first line's movement, the lot created for a found line,
 * the regularised flags and the closing; the count stays open. The service is built here with a failing
 * collaborator and run in a transaction like the real one.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class CountRegularisationAtomicityTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private InventoryCounts counts;

    @Autowired
    private InventoryCountRepository countRepository;

    @Autowired
    private CountLineRepository lineRepository;

    @Autowired
    private LotRepository lots;

    @Autowired
    private LotIdentities identities;

    @Autowired
    private StockMovementRepository movements;

    @Autowired
    private AuditEntryRepository auditEntries;

    @Autowired
    private CountQueries queries;

    @Autowired
    private ActorProvider actors;

    @Autowired
    private Clock clock;

    @Autowired
    private JsonMapper json;

    @Autowired
    private StockService stock;

    @Autowired
    private AuditRecorder audit;

    private long product;
    private long lot;
    private long count;
    private String found;

    /** Lot of 10 counted 8 (−2), and a lot found during the count (+4, created at regularisation). */
    @BeforeEach
    void countWithAFoundLot() {
        TestLots testLots = new TestLots(jdbc);
        String key = UUID.randomUUID().toString().substring(0, 8);
        product = testLots.product("FAULT-" + key, "Fault injection");
        lot = testLots.lot(product, "L-" + key, LocalDate.of(2027, 5, 31), "10");
        found = "F-" + key;
        TestActor.signIn(testLots.fixtureUser(), "inventory.count", "inventory.validate");
        count = counts.open(new OpenInput("cycle", null, List.of(product), null)).id();
        long line = jdbc.queryForObject("SELECT id FROM inventory_lines WHERE inventory_id = ?", Long.class, count);
        counts.enter(count, line, new BigDecimal("8"));
        counts.add(count, new AddInput(null, product, found, LocalDate.of(2028, 1, 31), new BigDecimal("0.5"),
                new BigDecimal("4")));
    }

    @AfterEach
    void signOut() {
        TestActor.signOut();
    }

    @Test
    void aFailureOnTheSecondLineRollsBackTheFirstAndTheCreatedLot() {
        AtomicInteger posts = new AtomicInteger();
        StockService failingOnSecond = new StockService(lots, movements, actors, clock) {
            @Override
            public List<Long> post(List<Movement> requests) {
                if (posts.incrementAndGet() == 2) {
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lots WHERE product_id = ?", Integer.class,
                            product)).as("found lot created before the second line is posted").isEqualTo(2);
                    throw new IllegalStateException("disk full");
                }
                return super.post(requests);
            }
        };

        assertThatThrownBy(() -> run(service(failingOnSecond, audit))).hasMessage("disk full");

        assertThat(posts).hasValue(2);
        assertNothingChanged();
    }

    @Test
    void aFailedAuditRollsEverythingBack() {
        AuditRecorder failingAudit = new AuditRecorder(auditEntries, actors, clock, json) {
            @Override
            public void record(AuditAction action, String entity, Long entityId, Map<String, ?> oldValues,
                    Map<String, ?> newValues) {
                throw new IllegalStateException("audit_log unavailable");
            }
        };

        assertThatThrownBy(() -> run(service(stock, failingAudit))).hasMessage("audit_log unavailable");

        assertNothingChanged();
    }

    private CountRegularisation service(StockService stockService, AuditRecorder auditRecorder) {
        return new CountRegularisation(countRepository, lineRepository, lots, identities, stockService,
                auditRecorder, queries, actors, clock);
    }

    private void run(CountRegularisation service) {
        transactions.executeWithoutResult(status -> service.regularize(count, null));
    }

    private void assertNothingChanged() {
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", String.class, lot))
                .isEqualTo("10.000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE lot_id = ?", Integer.class, lot))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lots WHERE product_id = ?", Integer.class, product))
                .as("no lot created for the found line").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM inventories WHERE id = ?", String.class, count))
                .isEqualTo("open");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_lines WHERE inventory_id = ? AND (regularized OR lot_id IS NULL)""",
                Integer.class, count)).as("the found line keeps no lot and nothing is regularised").isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE action = 'STOCK_CORRECTION' AND entity = 'lots' AND entity_id = ?""",
                Integer.class, lot)).isZero();
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
    }
}
