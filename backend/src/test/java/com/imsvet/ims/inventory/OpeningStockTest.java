package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.StockAssertions;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opening stock (W10a, FR-STOCK-007, BR-STOCK-014, ADR-082…084): only while {@code opening_stock_open}; lot number,
 * expiry and unit cost required (BR-LOT-001); quantity in a unit converted exactly; find-or-create by (product, lot
 * number) under lock (BR-LOT-002, ADR-070): another expiry, another cost or a recalled lot refused; expired accepted;
 * one opening_stock movement through StockService; audited OPENING_STOCK.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class OpeningStockTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    /** Direct SQL changes are seen once the persistence context of the test transaction is cleared. */
    @Autowired
    private EntityManager entityManager;

    private TestUser user;
    private ApiClient pharmacist;
    private TestLots lots;
    private long solution;
    private long bottle;

    @BeforeEach
    void product() throws Exception {
        clock.reset();
        user = new TestUsers(jdbc, encoder).create("Pharmacist");
        pharmacist = ApiClient.create(context, "198.51.100.210");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
        lots = new TestLots(jdbc);
        solution = idOf(pharmacist.post("/api/products", "{\"sku\":\"OPEN-ML\",\"commercialName\":\"Solution X\","
                + "\"baseUnit\":\"ml\",\"divisible\":true,\"purchasePrice\":\"0.02\"}").andExpect(status().isCreated()));
        bottle = idOf(pharmacist.post("/api/products/" + solution + "/units",
                "{\"name\":\"Bottle\",\"factorToBase\":\"100\",\"sellable\":true}").andExpect(status().isCreated()));
    }

    @Test
    void aNewLotIsCreatedWithItsOpeningMovementAndAudited() throws Exception {
        long lot = idOfLot(open("L2407A", "2027-03-31", "2", bottle, "0.0200").andExpect(status().isCreated())
                .andExpect(jsonPath("$.lot.lotNumber").value("L2407A"))
                .andExpect(jsonPath("$.lot.expiryDate").value("2027-03-31"))
                .andExpect(jsonPath("$.lot.quantityOnHand").value("200.000"))
                .andExpect(jsonPath("$.lot.unitCost").value("0.0200"))
                .andExpect(jsonPath("$.lot.status").value("active"))
                .andExpect(jsonPath("$.movement.type").value("opening_stock"))
                .andExpect(jsonPath("$.movement.quantity").value("200.000"))
                .andExpect(jsonPath("$.movement.user.id").value(user.id())));

        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT user_id, new_values->>'$.quantity' AS qty, new_values->>'$.lotNumber' AS lot_number,
                       new_values->'$.created' AS created
                FROM audit_log WHERE action = 'OPENING_STOCK' AND entity = 'lots' AND entity_id = ?""", lot);
        assertThat(audit).containsEntry("user_id", user.id()).containsEntry("qty", "200.000")
                .containsEntry("lot_number", "L2407A");
        assertThat(audit.get("created").toString()).isEqualTo("true");
        assertThat(jdbc.queryForObject("SELECT received_at FROM lots WHERE id = ?", String.class, lot))
                .startsWith("2026-10-20");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
    }

    @Test
    void theSameLotNumberAndExpiryAddsToTheExistingLotIgnoringCase() throws Exception {
        long first = idOfLot(open("L2407A", "2027-03-31", "50", null, "0.02"));

        long second = idOfLot(open("l2407a", "2027-03-31", "1", bottle, "0.0200").andExpect(status().isCreated())
                .andExpect(jsonPath("$.lot.quantityOnHand").value("150.000")));

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lots WHERE product_id = ?", Integer.class, solution))
                .isEqualTo(1);
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(first));
    }

    @Test
    void anotherExpiryOrARecalledLotIsRefused() throws Exception {
        long lot = idOfLot(open("L-1", "2027-03-31", "10", null, "0.02"));

        open("L-1", "2027-04-30", "1", null, "0.02").andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("LOT_EXPIRY_MISMATCH"));
        jdbc.update("UPDATE lots SET status = 'recalled' WHERE id = ?", lot);
        entityManager.clear();
        open("L-1", "2027-03-31", "1", null, "0.02").andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("LOT_RECALLED"));

        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", String.class, lot))
                .isEqualTo("10.000");
    }

    /** ADR-098 (ADR-084 amended): another cost is averaged on the current balance, 4 decimals HALF_UP. */
    @Test
    void anotherCostBecomesTheWeightedAverage() throws Exception {
        long lot = idOfLot(open("L-AVG", "2027-03-31", "10", null, "0.02"));

        open("L-AVG", "2027-03-31", "1", null, "0.03").andExpect(status().isCreated())
                .andExpect(jsonPath("$.lot.quantityOnHand").value("11.000"))
                .andExpect(jsonPath("$.lot.unitCost").value("0.0209"));

        assertThat(jdbc.queryForObject("SELECT unit_cost FROM lots WHERE id = ?", String.class, lot))
                .isEqualTo("0.0209");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
    }

    /** ADR-098: on an empty lot the entry cost replaces the old one (no division by zero). */
    @Test
    void anEmptyLotTakesTheEntryCost() throws Exception {
        long lot = lots.lot(solution, "L-EMPTY", TODAY.plusDays(300), "0");

        open("L-EMPTY", TODAY.plusDays(300).toString(), "4", null, "0.0375").andExpect(status().isCreated())
                .andExpect(jsonPath("$.lot.unitCost").value("0.0375"));

        assertThat(jdbc.queryForObject("SELECT unit_cost FROM lots WHERE id = ?", String.class, lot))
                .isEqualTo("0.0375");
    }

    @Test
    void aBlockedLotReceivesStockAndStaysBlocked() throws Exception {
        long lot = lots.lot(solution, "L-BLK", TODAY.plusDays(300), "5", "blocked");
        jdbc.update("UPDATE lots SET unit_cost = 0.02 WHERE id = ?", lot);

        open("L-BLK", TODAY.plusDays(300).toString(), "5", null, "0.02").andExpect(status().isCreated())
                .andExpect(jsonPath("$.lot.status").value("blocked"))
                .andExpect(jsonPath("$.lot.quantityOnHand").value("10.000"));
    }

    @Test
    void anExpiredLotIsAccepted() throws Exception {
        open("L-OLD", TODAY.minusDays(2).toString(), "3", null, "0").andExpect(status().isCreated())
                .andExpect(jsonPath("$.lot.status").value("expired"));
    }

    @Test
    void closedOpeningPeriodPointsToAnInventoryCount() throws Exception {
        jdbc.update("UPDATE app_settings SET setting_value = 'false' WHERE setting_key = 'opening_stock_open'");

        open("L-LATE", "2027-03-31", "1", null, "0.02").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPENING_STOCK_CLOSED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lots WHERE product_id = ?", Integer.class, solution))
                .isZero();
    }

    @Test
    void anArchivedProductIsRefused() throws Exception {
        pharmacist.post("/api/products/" + solution + "/archive", "").andExpect(status().isOk());

        open("L-ARC", "2027-03-31", "1", null, "0.02").andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));
    }

    @Test
    void invalidEntriesAreFieldErrors() throws Exception {
        long tablets = lots.product("OPEN-TAB", "Tablets");
        long otherUnit = idOf(pharmacist.post("/api/products/" + tablets + "/units",
                "{\"name\":\"Box\",\"factorToBase\":\"10\",\"sellable\":true}"));

        assertFieldError(open("", "2027-03-31", "1", null, "0.02"), "lotNumber", "REQUIRED");
        assertFieldError(open("x".repeat(61), "2027-03-31", "1", null, "0.02"), "lotNumber", "SIZE");
        assertFieldError(pharmacist.post("/api/inventory/opening-stock", "{\"productId\":" + solution
                + ",\"lotNumber\":\"L\",\"quantity\":\"1\",\"unitCost\":\"0\"}"), "expiryDate", "REQUIRED");
        assertFieldError(pharmacist.post("/api/inventory/opening-stock", "{\"productId\":" + solution
                + ",\"lotNumber\":\"L\",\"expiryDate\":\"2027-03-31\",\"quantity\":\"1\"}"), "unitCost", "REQUIRED");
        assertFieldError(open("L", "2027-03-31", "1", null, "-0.01"), "unitCost", "NOT_NEGATIVE");
        assertFieldError(open("L", "2027-03-31", "1", null, "0.00001"), "unitCost", "SCALE");
        assertFieldError(open("L", "2027-03-31", "0", null, "0.02"), "quantity", "POSITIVE");
        assertFieldError(open("L", "2027-03-31", "1", otherUnit, "0.02"), "unitId", "UNKNOWN");
        pharmacist.post("/api/inventory/opening-stock", "{\"productId\":999999,\"lotNumber\":\"L\","
                + "\"expiryDate\":\"2027-03-31\",\"quantity\":\"1\",\"unitCost\":\"0\"}").andExpect(status().isNotFound());
    }

    private ResultActions open(String lotNumber, String expiry, String quantity, Long unitId, String unitCost)
            throws Exception {
        return pharmacist.post("/api/inventory/opening-stock", "{\"productId\":" + solution + ",\"lotNumber\":\""
                + lotNumber + "\",\"expiryDate\":\"" + expiry + "\",\"quantity\":\"" + quantity + "\""
                + (unitId == null ? "" : ",\"unitId\":" + unitId) + ",\"unitCost\":\"" + unitCost + "\"}");
    }

    private static void assertFieldError(ResultActions actions, String field, String code) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code));
    }

    private static long idOf(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private static long idOfLot(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("lot").get("id").asLong();
    }
}
