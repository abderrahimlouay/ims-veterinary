package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.StockAssertions;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Regularising a count (W5, ADR-072/074/075): one {@code adjustment_inventory} per line with a non-zero variance,
 * reason {@code Inventory #<id>}, posted on the current balance (BR-INV-002/005/008); none for a zero variance;
 * lots found during the count created; audit {@code STOCK_CORRECTION} per lot (BR-INV-006); the count closes. Any
 * refusal posts nothing and leaves the count open (BR-INV-004/008).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CountRegularisationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate EXPIRY = LocalDate.of(2027, 5, 31);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    private ApiClient pharmacist;
    private TestUser user;
    private long tablets;
    private long l1;
    private long l2;
    private long l3;

    @BeforeEach
    void stock() throws Exception {
        clock.reset();
        TestLots lots = new TestLots(jdbc);
        tablets = lots.product("REG-TAB", "Regularised tablets");
        l1 = lots.lot(tablets, "R-L1", EXPIRY, "10");
        l2 = lots.lot(tablets, "R-L2", EXPIRY.plusMonths(1), "5");
        l3 = lots.lot(tablets, "R-L3", EXPIRY.plusMonths(2), "4");
        user = new TestUsers(jdbc, encoder).create("Pharmacist");
        pharmacist = ApiClient.create(context, "198.51.100.241");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void eachVarianceIsOneInventoryMovementAndTheCountCloses() throws Exception {
        long count = openCycle();
        enter(count, lineOf(count, l1), "8");
        enter(count, lineOf(count, l2), "5");
        enter(count, lineOf(count, l3), "6");

        regularize(count, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("closed"))
                .andExpect(jsonPath("$.closedBy.id").value(user.id()))
                .andExpect(jsonPath("$.closedAt").value("2026-10-20T08:00:00Z"))
                .andExpect(jsonPath("$.lines[?(@.lot.id == " + l2 + ")].adjustmentMovementId")
                        .value(contains(nullValue())))
                .andExpect(jsonPath("$.lines[*].regularized").value(everyItem(
                        is(true))));

        List<Map<String, Object>> posted = jdbc.queryForList("""
                SELECT lot_id, movement_type, quantity, reason, reference_type, reference_id FROM stock_movements
                WHERE lot_id IN (?, ?, ?) AND movement_type = 'adjustment_inventory' ORDER BY lot_id""", l1, l2, l3);
        assertThat(posted).hasSize(2);
        assertThat(posted.get(0)).containsEntry("lot_id", l1).containsEntry("reason", "Inventory #" + count)
                .containsEntry("reference_type", "inventory").containsEntry("reference_id", count);
        assertThat(posted.get(0).get("quantity").toString()).isEqualTo("-2.000");
        assertThat(posted.get(1)).containsEntry("lot_id", l3);
        assertThat(posted.get(1).get("quantity").toString()).isEqualTo("2.000");
        assertThat(balance(l1)).isEqualTo("8.000");
        assertThat(balance(l2)).isEqualTo("5.000");
        assertThat(balance(l3)).isEqualTo("6.000");
        assertThat(jdbc.queryForList("""
                SELECT entity_id, JSON_UNQUOTE(JSON_EXTRACT(old_values, '$.quantity')) AS before_qty,
                       JSON_UNQUOTE(JSON_EXTRACT(new_values, '$.quantity')) AS after_qty
                FROM audit_log WHERE action = 'STOCK_CORRECTION' AND entity = 'lots' AND entity_id IN (?, ?, ?)
                ORDER BY entity_id""", l1, l2, l3)).containsExactly(
                Map.of("entity_id", l1, "before_qty", "10.000", "after_qty", "8.000"),
                Map.of("entity_id", l3, "before_qty", "4.000", "after_qty", "6.000"));
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(l1, l2, l3));
    }

    @Test
    void theVarianceIsPostedOnTheCurrentBalance() throws Exception {
        long count = openCycle();
        enter(count, lineOf(count, l1), "9");
        withdraw(l1, "2");

        regularize(count, "{}").andExpect(status().isOk());

        assertThat(balance(l1)).isEqualTo("7.000");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(l1));
    }

    @Test
    void aBalanceThatWouldGoNegativeRefusesEverythingAndKeepsTheCountOpen() throws Exception {
        long count = openCycle();
        enter(count, lineOf(count, l1), "8");
        long line = lineOf(count, l2);
        enter(count, line, "0");
        withdraw(l2, "3");

        regularize(count, "{}").andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"))
                .andExpect(jsonPath("$.context.lineId").value(line))
                .andExpect(jsonPath("$.context.lotId").value(l2));

        assertThat(balance(l1)).isEqualTo("10.000");
        assertThat(balance(l2)).isEqualTo("2.000");
        assertThat(inventoryMovements()).isZero();
        assertThat(countStatus(count)).isEqualTo("open");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_lines WHERE inventory_id = ? AND regularized",
                Integer.class, count)).isZero();
    }

    @Test
    void onlyTheAcceptedLinesArePosted() throws Exception {
        long count = openCycle();
        long first = lineOf(count, l1);
        enter(count, first, "8");
        enter(count, lineOf(count, l3), "6");

        regularize(count, "{\"lineIds\":[" + first + "]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[?(@.lot.id == " + l3 + ")].regularized")
                        .value(contains(false)));

        assertThat(balance(l1)).isEqualTo("8.000");
        assertThat(balance(l3)).isEqualTo("4.000");
    }

    @Test
    void aClosedCountOrARegularisedLineIsNeverPostedTwice() throws Exception {
        long count = openCycle();
        long line = lineOf(count, l1);
        enter(count, line, "8");
        regularize(count, "{}").andExpect(status().isOk());

        regularize(count, "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_NOT_OPEN"));
        regularize(count, "{\"lineIds\":[" + line + "]}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_LINE_ALREADY_REGULARIZED"))
                .andExpect(jsonPath("$.context.lineId").value(line));
        assertThat(inventoryMovements()).isEqualTo(1);
    }

    @Test
    void acceptedLinesMustBeCountedLinesOfTheCount() throws Exception {
        long count = openCycle();
        long uncounted = lineOf(count, l2);

        assertFieldError(regularize(count, "{\"lineIds\":[999999]}"), "lineIds", "UNKNOWN");
        assertFieldError(regularize(count, "{\"lineIds\":[" + uncounted + "]}"), "lineIds", "NOT_COUNTED");
        assertThat(countStatus(count)).isEqualTo("open");
    }

    @Test
    void unsellableLotsAreRegularisedToo() throws Exception {
        TestLots lots = new TestLots(jdbc);
        long expired = lots.lot(tablets, "R-EXP", LocalDate.of(2026, 9, 30), "3");
        long blocked = lots.lot(tablets, "R-BLK", EXPIRY, "3", "blocked");
        long recalled = lots.lot(tablets, "R-RCL", EXPIRY, "3", "recalled");
        long count = openCycle();
        enter(count, lineOf(count, expired), "0");
        enter(count, lineOf(count, blocked), "2");
        enter(count, lineOf(count, recalled), "1");

        regularize(count, "{}").andExpect(status().isOk());

        assertThat(balance(expired)).isEqualTo("0.000");
        assertThat(balance(blocked)).isEqualTo("2.000");
        assertThat(balance(recalled)).isEqualTo("1.000");
        assertThat(jdbc.queryForObject("SELECT status FROM lots WHERE id = ?", String.class, blocked))
                .isEqualTo("blocked");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(expired, blocked, recalled));
    }

    @Test
    void aLotFoundDuringTheCountIsCreatedWithItsQuantity() throws Exception {
        long count = openCycle();
        pharmacist.post("/api/inventory/counts/" + count + "/lines", found("R-NEW", EXPIRY, "4"))
                .andExpect(status().isCreated());

        regularize(count, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[?(@.lot.lotNumber == 'R-NEW')].regularized")
                        .value(contains(true)));

        Map<String, Object> lot = jdbc.queryForMap("""
                SELECT id, expiry_date, unit_cost, quantity_on_hand, status FROM lots
                WHERE product_id = ? AND lot_number = 'R-NEW'""", tablets);
        assertThat(lot.get("quantity_on_hand").toString()).isEqualTo("4.000");
        assertThat(lot.get("unit_cost").toString()).isEqualTo("0.7500");
        assertThat(lot).containsEntry("status", "active");
        long id = ((Number) lot.get("id")).longValue();
        assertThat(jdbc.queryForObject("SELECT lot_id FROM inventory_lines WHERE inventory_id = ? AND lot_id = ?",
                Long.class, count, id)).isEqualTo(id);
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(id));
    }

    @Test
    void aFoundLotNumberNowTakenWithAnotherExpiryIsRefusedBeforeAnythingIsPosted() throws Exception {
        long count = openCycle();
        enter(count, lineOf(count, l1), "8");
        pharmacist.post("/api/inventory/counts/" + count + "/lines", found("R-LATE", EXPIRY, "4"))
                .andExpect(status().isCreated());
        new TestLots(jdbc).lot(tablets, "R-LATE", EXPIRY.plusYears(1), "1");

        regularize(count, "{}").andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("LOT_EXPIRY_MISMATCH"));

        assertThat(balance(l1)).isEqualTo("10.000");
        assertThat(inventoryMovements()).isZero();
        assertThat(countStatus(count)).isEqualTo("open");
    }

    private long openCycle() throws Exception {
        String body = pharmacist.post("/api/inventory/counts", "{\"type\":\"cycle\",\"productIds\":[" + tablets + "]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asLong();
    }

    private void enter(long count, long line, String quantity) throws Exception {
        pharmacist.perform(MockMvcRequestBuilders.put("/api/inventory/counts/" + count + "/lines/" + line)
                .contentType(MediaType.APPLICATION_JSON).content("{\"countedQty\":\"" + quantity + "\"}"), true)
                .andExpect(status().isOk());
    }

    private ResultActions regularize(long count, String body) throws Exception {
        return pharmacist.post("/api/inventory/counts/" + count + "/regularize", body);
    }

    private void withdraw(long lot, String quantity) throws Exception {
        pharmacist.post("/api/inventory/adjustments", "{\"lotId\":" + lot + ",\"type\":\"adjustment_breakage\","
                + "\"quantity\":\"" + quantity + "\",\"reason\":\"Broken during the count\"}").andExpect(status().isCreated());
    }

    private String found(String lotNumber, LocalDate expiry, String counted) {
        return "{\"productId\":" + tablets + ",\"lotNumber\":\"" + lotNumber + "\",\"expiryDate\":\"" + expiry
                + "\",\"unitCost\":\"0.75\",\"countedQty\":\"" + counted + "\"}";
    }

    private long lineOf(long count, long lot) throws Exception {
        JsonNode detail = JSON.readTree(pharmacist.get("/api/inventory/counts/" + count).andReturn().getResponse()
                .getContentAsString());
        for (JsonNode line : detail.get("lines")) {
            if (!line.get("lot").isNull() && line.get("lot").get("id").asLong() == lot) {
                return line.get("id").asLong();
            }
        }
        throw new AssertionError("No line for lot " + lot);
    }

    private String balance(long lot) {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", String.class, lot);
    }

    private String countStatus(long count) {
        return jdbc.queryForObject("SELECT status FROM inventories WHERE id = ?", String.class, count);
    }

    private int inventoryMovements() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM stock_movements m JOIN lots l ON l.id = m.lot_id
                WHERE l.product_id = ? AND m.movement_type = 'adjustment_inventory'""", Integer.class, tablets);
    }

    private static void assertFieldError(ResultActions actions, String field, String code) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code));
    }
}
