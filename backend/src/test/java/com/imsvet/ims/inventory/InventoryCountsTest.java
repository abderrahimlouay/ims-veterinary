package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
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
import org.junit.jupiter.api.AfterEach;
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
 * Inventory counts before regularisation (W5, FR-STOCK-006): opening full and cycle counts (BR-INV-003/007,
 * ADR-073), entering counted quantities with a watermark (BR-INV-001/005/008, ADR-072), adding lots without a line
 * or unknown to the system (ADR-074), cancelling, and refusals on closed counts (BR-INV-004). Movements during the
 * count are manual adjustments and opening stock (sales from Phase 7).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class InventoryCountsTest {

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

    private TestUser user;
    private ApiClient pharmacist;
    private long tablets;
    private long syrup;
    private long l1;
    private long l2;
    private long empty;
    private long q1;
    private int category;

    @BeforeEach
    void stock() throws Exception {
        clock.reset();
        TestLots lots = new TestLots(jdbc);
        tablets = lots.product("CNT-TAB", "Count tablets");
        syrup = lots.product("CNT-SYR", "Count syrup");
        category = jdbc.queryForObject("SELECT id FROM therapeutic_categories ORDER BY id LIMIT 1", Integer.class);
        jdbc.update("UPDATE products SET category_id = ?, purchase_price = 0.5 WHERE id = ?", category, tablets);
        l1 = lots.lot(tablets, "C-L1", EXPIRY, "10");
        l2 = lots.lot(tablets, "C-L2", EXPIRY.plusMonths(1), "5");
        empty = lots.lot(tablets, "C-L0", EXPIRY.plusMonths(2), "0");
        q1 = lots.lot(syrup, "C-Q1", EXPIRY, "7");
        user = new TestUsers(jdbc, encoder).create("Pharmacist");
        jdbc.update("UPDATE users SET full_name = 'Amina Benali' WHERE id = ?", user.id());
        pharmacist = ApiClient.create(context, "198.51.100.240");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
    }

    /** Counting never moves stock; the movements made during the counts keep ledger = balance (BR-STOCK-007). */
    @AfterEach
    void ledgerMatchesBalances() {
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(l1, l2, empty, q1));
    }

    @Test
    void aFullCountListsEveryLotWithStockAndOnlyOneMayBeOpen() throws Exception {
        long count = idOf(open("{\"type\":\"full\",\"notes\":\"Year end\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("full"))
                .andExpect(jsonPath("$.status").value("open"))
                .andExpect(jsonPath("$.notes").value("Year end"))
                .andExpect(jsonPath("$.startedBy.fullName").value("Amina Benali"))
                .andExpect(jsonPath("$.startedAt").value("2026-10-20T08:00:00Z")));

        pharmacist.get("/api/inventory/counts/" + count).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[?(@.lot.id == " + l1 + ")].theoreticalQty").value("10.000"))
                .andExpect(jsonPath("$.lines[?(@.lot.id == " + l2 + ")].theoreticalQty").value("5.000"))
                .andExpect(jsonPath("$.lines[?(@.lot.id == " + q1 + ")].theoreticalQty").value("7.000"))
                .andExpect(jsonPath("$.lines[?(@.lot.id == " + empty + ")]").isEmpty())
                .andExpect(jsonPath("$.lines[?(@.lot.id == " + l1 + ")].countedQty").value(contains(nullValue())));
        open("{\"type\":\"full\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_ALREADY_OPEN"));
        pharmacist.get("/api/inventory/counts?status=open").andExpect(jsonPath("$.content[0].id").value(count))
                .andExpect(jsonPath("$.content[0].countedCount").value(0));
    }

    @Test
    void aCycleCountIsScopedByCategoryOrProductList() throws Exception {
        long byCategory = idOf(open("{\"type\":\"cycle\",\"categoryId\":" + category + "}"));
        pharmacist.get("/api/inventory/counts/" + byCategory)
                .andExpect(jsonPath("$.scope.categoryId").value(category))
                .andExpect(jsonPath("$.lines[*].lot.id", containsInAnyOrder((int) l1, (int) l2)));

        long byProducts = idOf(open("{\"type\":\"cycle\",\"productIds\":[" + syrup + "]}"));
        pharmacist.get("/api/inventory/counts/" + byProducts)
                .andExpect(jsonPath("$.scope.productIds[0]").value(syrup))
                .andExpect(jsonPath("$.lines[*].lot.id", containsInAnyOrder((int) q1)));

        assertFieldError(open("{\"type\":\"cycle\"}"), "scope", "REQUIRED");
        assertFieldError(open("{\"type\":\"cycle\",\"categoryId\":" + category + ",\"productIds\":[" + syrup + "]}"),
                "scope", "ONE_OF");
        assertFieldError(open("{\"type\":\"full\",\"productIds\":[" + syrup + "]}"), "scope", "NOT_ALLOWED");
        assertFieldError(open("{\"type\":\"cycle\",\"productIds\":[999999]}"), "productIds", "UNKNOWN");
        assertFieldError(open("{\"type\":\"cycle\",\"categoryId\":999999}"), "categoryId", "UNKNOWN");
        assertFieldError(open("{\"type\":\"weekly\"}"), "type", "UNKNOWN");
    }

    @Test
    void enteringACountTakesTheWatermark() throws Exception {
        long count = idOf(open("{\"type\":\"full\"}"));
        long line = lineOf(count, l1);

        enter(count, line, "9").andExpect(status().isOk())
                .andExpect(jsonPath("$.countedQty").value("9.000"))
                .andExpect(jsonPath("$.balanceAtCount").value("10.000"))
                .andExpect(jsonPath("$.varianceQty").value("-1.000"))
                .andExpect(jsonPath("$.movementsSinceCount").value("0.000"))
                .andExpect(jsonPath("$.currentBalance").value("10.000"))
                .andExpect(jsonPath("$.countedBy.fullName").value("Amina Benali"))
                .andExpect(jsonPath("$.countedAt").value("2026-10-20T08:00:00Z"))
                .andExpect(jsonPath("$.regularized").value(false));
        assertThat(jdbc.queryForObject("SELECT CAST(variance_qty AS CHAR) FROM inventory_lines WHERE id = ?", String.class,
                line)).as("generated column = service variance (ADR-016)").isEqualTo("-1.000");
    }

    @Test
    void aWithdrawalAfterTheLotWasCountedMakesNoFalseVariance() throws Exception {
        long count = idOf(open("{\"type\":\"full\"}"));
        long line = lineOf(count, l1);
        enter(count, line, "9");

        withdraw(l1, "2");

        lineView(count, line).andExpect(jsonPath("$.lines[?(@.id == " + line + ")].varianceQty").value("-1.000"))
                .andExpect(jsonPath("$.lines[?(@.id == " + line + ")].movementsSinceCount").value("-2.000"))
                .andExpect(jsonPath("$.lines[?(@.id == " + line + ")].currentBalance").value("8.000"));
    }

    @Test
    void aWithdrawalBeforeTheLotWasCountedIsInTheBalanceAtCount() throws Exception {
        long count = idOf(open("{\"type\":\"full\"}"));
        long line = lineOf(count, l1);
        withdraw(l1, "2");

        enter(count, line, "8").andExpect(jsonPath("$.balanceAtCount").value("8.000"))
                .andExpect(jsonPath("$.varianceQty").value("0.000"));
        lineView(count, line).andExpect(jsonPath("$.lines[?(@.id == " + line + ")].theoreticalQty").value("10.000"));
    }

    @Test
    void editingALineTakesANewWatermark() throws Exception {
        long count = idOf(open("{\"type\":\"full\"}"));
        long line = lineOf(count, l1);
        enter(count, line, "9");
        withdraw(l1, "2");

        enter(count, line, "7").andExpect(jsonPath("$.balanceAtCount").value("8.000"))
                .andExpect(jsonPath("$.varianceQty").value("-1.000"))
                .andExpect(jsonPath("$.movementsSinceCount").value("0.000"));
    }

    @Test
    void stockReceivedDuringTheCountStaysOutOfTheVariance() throws Exception {
        long count = idOf(open("{\"type\":\"full\"}"));
        long line = lineOf(count, l1);
        enter(count, line, "9");

        pharmacist.post("/api/inventory/opening-stock", "{\"productId\":" + tablets + ",\"lotNumber\":\"C-L1\","
                + "\"expiryDate\":\"" + EXPIRY + "\",\"quantity\":\"5\",\"unitCost\":\"0.5\"}").andExpect(status().isCreated());

        lineView(count, line).andExpect(jsonPath("$.lines[?(@.id == " + line + ")].varianceQty").value("-1.000"))
                .andExpect(jsonPath("$.lines[?(@.id == " + line + ")].movementsSinceCount").value("5.000"))
                .andExpect(jsonPath("$.lines[?(@.id == " + line + ")].currentBalance").value("15.000"));
    }

    @Test
    void aLotWithoutALineCanBeAddedOnceWithinTheScope() throws Exception {
        long count = idOf(open("{\"type\":\"cycle\",\"categoryId\":" + category + "}"));

        pharmacist.post("/api/inventory/counts/" + count + "/lines", "{\"lotId\":" + empty + ",\"countedQty\":\"3\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lot.id").value(empty))
                .andExpect(jsonPath("$.theoreticalQty").value("0.000"))
                .andExpect(jsonPath("$.balanceAtCount").value("0.000"))
                .andExpect(jsonPath("$.varianceQty").value("3.000"));
        assertFieldError(pharmacist.post("/api/inventory/counts/" + count + "/lines",
                "{\"lotId\":" + empty + ",\"countedQty\":\"3\"}"), "lotId", "DUPLICATE");
        assertFieldError(pharmacist.post("/api/inventory/counts/" + count + "/lines",
                "{\"lotId\":" + q1 + ",\"countedQty\":\"1\"}"), "productId", "OUT_OF_SCOPE");
    }

    @Test
    void aLotUnknownToTheSystemIsRecordedForRegularisation() throws Exception {
        long count = idOf(open("{\"type\":\"full\"}"));

        pharmacist.post("/api/inventory/counts/" + count + "/lines", found("NEW-1", EXPIRY.toString(), "4"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lot").doesNotExist())
                .andExpect(jsonPath("$.foundLot.lotNumber").value("NEW-1"))
                .andExpect(jsonPath("$.foundLot.expiryDate").value("2027-05-31"))
                .andExpect(jsonPath("$.foundLot.unitCost").value("0.5000"))
                .andExpect(jsonPath("$.balanceAtCount").value("0.000"))
                .andExpect(jsonPath("$.varianceQty").value("4.000"));
        assertFieldError(pharmacist.post("/api/inventory/counts/" + count + "/lines", found("new-1", EXPIRY.toString(),
                "1")), "lotNumber", "DUPLICATE");
        pharmacist.post("/api/inventory/counts/" + count + "/lines", found("C-L1", "2030-01-31", "1"))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("LOT_EXPIRY_MISMATCH"));
        assertFieldError(pharmacist.post("/api/inventory/counts/" + count + "/lines", found("C-L1", EXPIRY.toString(),
                "1")), "lotId", "DUPLICATE");
        assertFieldError(pharmacist.post("/api/inventory/counts/" + count + "/lines", found("NEW-2", EXPIRY.toString(),
                "0")), "countedQty", "POSITIVE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lots WHERE lot_number LIKE 'NEW-%'", Long.class)).isZero();
    }

    @Test
    void countedQuantitiesAreCheckedLikeStock() throws Exception {
        long count = idOf(open("{\"type\":\"full\"}"));
        long line = lineOf(count, l1);

        assertFieldError(enter(count, line, "-1"), "countedQty", "NOT_NEGATIVE");
        assertFieldError(enter(count, line, "1.5"), "countedQty", "WHOLE_NUMBER");
        pharmacist.perform(MockMvcRequestBuilders.put("/api/inventory/counts/" + count + "/lines/999999")
                .contentType(MediaType.APPLICATION_JSON).content("{\"countedQty\":\"1\"}"), true)
                .andExpect(status().isNotFound());
    }

    @Test
    void aCancelledCountCanNoLongerChange() throws Exception {
        long count = idOf(open("{\"type\":\"full\"}"));
        long line = lineOf(count, l1);

        pharmacist.post("/api/inventory/counts/" + count + "/cancel", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.closedBy.fullName").value("Amina Benali"));

        enter(count, line, "9").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVENTORY_NOT_OPEN"));
        pharmacist.post("/api/inventory/counts/" + count + "/cancel", "").andExpect(status().isConflict());
        pharmacist.post("/api/inventory/counts/" + count + "/lines", found("NEW-3", EXPIRY.toString(), "1"))
                .andExpect(status().isConflict());
        open("{\"type\":\"full\"}").andExpect(status().isCreated());
    }

    private ResultActions open(String body) throws Exception {
        return pharmacist.post("/api/inventory/counts", body);
    }

    private ResultActions enter(long count, long line, String quantity) throws Exception {
        return pharmacist.perform(MockMvcRequestBuilders.put("/api/inventory/counts/" + count + "/lines/" + line)
                .contentType(MediaType.APPLICATION_JSON).content("{\"countedQty\":\"" + quantity + "\"}"), true);
    }

    /** The count detail, where {@code line} is checked with a filter on its id. */
    private ResultActions lineView(long count, long line) throws Exception {
        return pharmacist.get("/api/inventory/counts/" + count).andExpect(status().isOk());
    }

    private void withdraw(long lot, String quantity) throws Exception {
        pharmacist.post("/api/inventory/adjustments", "{\"lotId\":" + lot + ",\"type\":\"adjustment_breakage\","
                + "\"quantity\":\"" + quantity + "\",\"reason\":\"Broken during the count\"}").andExpect(status().isCreated());
    }

    private String found(String lotNumber, String expiry, String counted) {
        return "{\"productId\":" + tablets + ",\"lotNumber\":\"" + lotNumber + "\",\"expiryDate\":\"" + expiry
                + "\",\"unitCost\":\"0.5\",\"countedQty\":\"" + counted + "\"}";
    }

    private long lineOf(long count, long lot) throws Exception {
        JsonNode detail = JSON.readTree(pharmacist.get("/api/inventory/counts/" + count).andReturn().getResponse()
                .getContentAsString());
        for (JsonNode line : detail.get("lines")) {
            if (line.get("lot") != null && !line.get("lot").isNull() && line.get("lot").get("id").asLong() == lot) {
                return line.get("id").asLong();
            }
        }
        throw new AssertionError("No line for lot " + lot);
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
}
