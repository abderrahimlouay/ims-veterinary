package com.imsvet.ims.purchasing;

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
import java.time.Duration;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cancelling a whole goods receipt (BR-PURCH-012, ADR-103, ADR-106, W1d): reason required; refused once a lot has
 * moved since (naming the lot and the movement); one receipt_cancellation per line, ledger = balance; the number
 * kept; audited RECEIPT_CANCEL; lot cost restored, a created lot back at 0 without supplier; a lot the receipt
 * blocked unblocked (LOT_UNBLOCK) only while still in the state the receipt set; the order's quantities back, a
 * forced close kept; the receipt's price entries marked cancelled and ignored by the prefill.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class GoodsReceiptCancellationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    @Autowired
    private EntityManager entityManager;

    private TestLots lots;
    private ApiClient pharmacist;
    private long supplier;
    private long tablets;

    @BeforeEach
    void data() throws Exception {
        clock.reset();
        lots = new TestLots(jdbc);
        jdbc.update("INSERT INTO suppliers (name, created_at, updated_at) VALUES ('Cancel supplier', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        supplier = jdbc.queryForObject("SELECT id FROM suppliers WHERE name = 'Cancel supplier'", Long.class);
        tablets = lots.product("GRC-TAB", "Cancelled tablets");
        jdbc.update("UPDATE products SET purchase_price = 0.2000 WHERE id = ?", tablets);
        TestUser user = new TestUsers(jdbc, encoder).create("Pharmacist");
        pharmacist = ApiClient.create(context, "198.51.100.246");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void aCancelledReceiptReversesItsStockKeepsItsNumberAndIsAudited() throws Exception {
        JsonNode receipt = receive(free(line(null, "L-NEW", "6", "0.3500", true)));
        long id = receipt.get("id").asLong();
        long lot = lotOf(receipt);

        cancel(id, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"));
        cancel(id, "Wrong supplier").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.number").value(receipt.get("number").asString()))
                .andExpect(jsonPath("$.cancelReason").value("Wrong supplier"))
                .andExpect(jsonPath("$.cancelledBy.fullName").isNotEmpty())
                .andExpect(jsonPath("$.cancelledAt").value("2026-10-20T08:00:00Z"));

        assertThat(jdbc.queryForMap("""
                SELECT movement_type, CAST(quantity AS CHAR) AS qty, reference_type, reference_id FROM stock_movements
                WHERE lot_id = ? ORDER BY id DESC LIMIT 1""", lot)).containsAllEntriesOf(Map.of(
                "movement_type", "receipt_cancellation", "qty", "-6.000", "reference_type", "goods_receipt",
                "reference_id", id));
        assertThat(jdbc.queryForMap("""
                SELECT CAST(quantity_on_hand AS CHAR) AS qty, CAST(unit_cost AS CHAR) AS cost, supplier_id
                FROM lots WHERE id = ?""", lot)).containsEntry("qty", "0.000").containsEntry("cost", "0.3500")
                .containsEntry("supplier_id", null);
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
        assertThat(jdbc.queryForMap("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(new_values, '$.reason')) AS reason,
                       JSON_UNQUOTE(JSON_EXTRACT(old_values, '$.status')) AS previous FROM audit_log
                WHERE action = 'RECEIPT_CANCEL' AND entity = 'goods_receipts' AND entity_id = ?""", id))
                .containsAllEntriesOf(Map.of("reason", "Wrong supplier", "previous", "confirmed"));
        cancel(id, "Again").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        cancel(999_999, "Gone").andExpect(status().isNotFound());
        pharmacist.get("/api/goods-receipts?status=cancelled&supplierId=" + supplier)
                .andExpect(jsonPath("$.content[0].id").value(id));
    }

    @Test
    void anExistingLotGetsItsCostBackAndKeepsItsSupplier() throws Exception {
        long other = otherSupplier();
        long lot = lots.lot(tablets, "L-OLD", LocalDate.of(2027, 6, 30), "10");
        jdbc.update("UPDATE lots SET unit_cost = 0.3000, supplier_id = ? WHERE id = ?", other, lot);
        long id = receive(free(line(null, "L-OLD", "5", "0.3500", true))).get("id").asLong();
        assertThat(cost(lot)).isEqualTo("0.3167");

        cancel(id, "Typing error").andExpect(status().isOk());

        assertThat(cost(lot)).isEqualTo("0.3000");
        assertThat(jdbc.queryForMap("SELECT CAST(quantity_on_hand AS CHAR) AS qty, supplier_id FROM lots WHERE id = ?",
                lot)).containsAllEntriesOf(Map.of("qty", "10.000", "supplier_id", other));
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
    }

    @Test
    void aLaterMovementOnALotMakesTheReceiptNotCancellable() throws Exception {
        JsonNode receipt = receive(free(line(null, "L-A", "4", "0.3", true) + "," + line(null, "L-B", "4", "0.3", true)));
        long lotB = receipt.get("lines").get(1).get("lotId").asLong();
        clock.advance(Duration.ofMinutes(5));
        JsonNode breakage = read(pharmacist.post("/api/inventory/adjustments", "{\"lotId\":" + lotB
                + ",\"type\":\"adjustment_breakage\",\"quantity\":\"1\",\"reason\":\"Dropped\"}")
                .andExpect(status().isCreated()));

        cancel(receipt.get("id").asLong(), "Too late").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_NOT_CANCELLABLE"))
                .andExpect(jsonPath("$.context.lotId").value(lotB))
                .andExpect(jsonPath("$.context.lotNumber").value("L-B"))
                .andExpect(jsonPath("$.context.movementId").value(breakage.get("id").asLong()))
                .andExpect(jsonPath("$.context.movementType").value("adjustment_breakage"))
                .andExpect(jsonPath("$.context.movementAt").value("2026-10-20T08:05:00Z"));
    }

    @Test
    void theOrderGetsItsQuantitiesBackAndAForcedCloseStaysClosed() throws Exception {
        JsonNode order = sentOrder("10");
        long orderId = order.get("id").asLong();
        long poLine = order.get("lines").get(0).get("id").asLong();
        long first = receive(onOrder(orderId, line(poLine, "O-1", "4", "0.3", true))).get("id").asLong();
        long second = receive(onOrder(orderId, line(poLine, "O-2", "6", "0.3", true))).get("id").asLong();
        orderStatus(orderId, "closed", "10.000");

        cancel(second, "Returned to the van").andExpect(status().isOk())
                .andExpect(jsonPath("$.purchaseOrder.status").value("partially_received"));
        orderStatus(orderId, "partially_received", "4.000");
        pharmacist.get("/api/purchase-orders/" + orderId).andExpect(jsonPath("$.closedAt").isEmpty());
        cancel(first, "Wrong order").andExpect(status().isOk());
        orderStatus(orderId, "sent", "0.000");

        JsonNode forced = sentOrder("10");
        long forcedId = forced.get("id").asLong();
        long receiptId = receive(onOrder(forcedId, line(forced.get("lines").get(0).get("id").asLong(), "F-1", "3",
                "0.3", true))).get("id").asLong();
        pharmacist.post("/api/purchase-orders/" + forcedId + "/close", "{\"reason\":\"Rest discontinued\"}")
                .andExpect(status().isOk());
        cancel(receiptId, "Damaged in transit").andExpect(status().isOk());
        orderStatus(forcedId, "closed", "0.000");
        pharmacist.get("/api/purchase-orders/" + forcedId)
                .andExpect(jsonPath("$.closedReason").value("Rest discontinued"));
    }

    /** ADR-106: the status is restored only while the lot is still blocked as the receipt left it. */
    @Test
    void aLotTheReceiptBlockedIsUnblockedOnlyWhileStillInThatState() throws Exception {
        long untouched = lotOf(receive(free(line(null, "B-1", "2", "0.3", false))), 0);
        JsonNode unblockedSince = receive(free(line(null, "B-2", "2", "0.3", false)));
        JsonNode reblockedSince = receive(free(line(null, "B-3", "2", "0.3", false)));
        JsonNode recalledSince = receive(free(line(null, "B-4", "2", "0.3", false)));
        long stocked = lots.lot(tablets, "B-5", LocalDate.of(2027, 6, 30), "8");
        JsonNode existing = receive(free(line(null, "B-5", "2", "0.3", false)).replace("{\"supplierId\"",
                "{\"confirmLotBlocking\":true,\"supplierId\""));
        clock.advance(Duration.ofMinutes(1));
        lotAction(lotOf(unblockedSince), "unblock", "Checked, fine");
        lotAction(lotOf(reblockedSince), "unblock", "Checked");
        lotAction(lotOf(reblockedSince), "block", "Damaged boxes");
        lotAction(lotOf(recalledSince), "recall", "Manufacturer notice");

        for (JsonNode r : List.of(unblockedSince, reblockedSince, recalledSince, existing)) {
            cancel(r.get("id").asLong(), "Cancelled").andExpect(status().isOk());
        }
        cancel(receiptOf(untouched), "Cancelled").andExpect(status().isOk());

        assertThat(statusAndReason(untouched)).containsExactly("active", null);
        assertThat(statusAndReason(lotOf(unblockedSince))).containsExactly("active", null);
        assertThat(statusAndReason(lotOf(reblockedSince))).containsExactly("blocked", "Damaged boxes");
        assertThat(statusAndReason(lotOf(recalledSince))).containsExactly("recalled", "Manufacturer notice");
        assertThat(statusAndReason(stocked)).containsExactly("active", null);
        assertThat(jdbc.queryForObject("SELECT CAST(quantity_on_hand AS CHAR) FROM lots WHERE id = ?", String.class,
                stocked)).isEqualTo("8.000");
        assertThat(jdbc.queryForList("""
                SELECT entity_id FROM audit_log WHERE action = 'LOT_UNBLOCK'
                  AND JSON_UNQUOTE(JSON_EXTRACT(new_values, '$.reason')) LIKE 'Receipt GR-%cancelled: Cancelled'
                ORDER BY entity_id""", Long.class)).containsExactlyInAnyOrder(untouched, stocked);
    }

    /** ADR-106: a lot the receipt did not block, blocked or recalled by a person since, keeps that status. */
    @Test
    void aLotBlockedOrRecalledByAPersonAfterTheReceiptKeepsThatStatus() throws Exception {
        long blocked = lotOf(receive(free(line(null, "P-BLK", "3", "0.3", true))));
        long recalled = lotOf(receive(free(line(null, "P-RCL", "3", "0.3", true))));
        clock.advance(Duration.ofMinutes(1));
        lotAction(blocked, "block", "Leaking vials");
        lotAction(recalled, "recall", "Manufacturer notice");

        cancel(receiptOf(blocked), "Cancelled").andExpect(status().isOk());
        cancel(receiptOf(recalled), "Cancelled").andExpect(status().isOk());

        assertThat(statusAndReason(blocked)).containsExactly("blocked", "Leaking vials");
        assertThat(statusAndReason(recalled)).containsExactly("recalled", "Manufacturer notice");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE action = 'LOT_UNBLOCK' AND entity_id IN (?, ?)""", Integer.class,
                blocked, recalled)).isZero();
    }

    /** ADR-106, SD-132: the receipt's price entry stays, marked cancelled, and the prefill no longer uses it. */
    @Test
    void theReceiptsPriceEntryIsMarkedCancelledAndIgnoredByThePrefill() throws Exception {
        long id = receive(free(line(null, "P-1", "3", "0.3300", true))).get("id").asLong();
        prefill().andExpect(jsonPath("$.unitPrice").value("0.3300")).andExpect(jsonPath("$.source").value("history"));

        cancel(id, "Wrong price list").andExpect(status().isOk());

        prefill().andExpect(jsonPath("$.unitPrice").value("0.2000")).andExpect(jsonPath("$.source").value("product"));
        pharmacist.get("/api/suppliers/" + supplier + "/prices?productId=" + tablets)
                .andExpect(jsonPath("$.content[0].receiptId").value(id))
                .andExpect(jsonPath("$.content[0].cancelled").value(true));
        long again = receive(free(line(null, "P-2", "3", "0.3300", true))).get("id").asLong();
        assertThat(jdbc.queryForList("""
                SELECT receipt_id FROM supplier_price_history WHERE supplier_id = ? AND cancelled = FALSE""",
                Long.class, supplier)).as("a later receipt compares with the latest entry not cancelled")
                .containsExactly(again);
    }

    private void lotAction(long lot, String action, String reason) throws Exception {
        pharmacist.post("/api/lots/" + lot + "/" + action, "{\"reason\":\"" + reason + "\"}").andExpect(status().isOk());
    }

    private List<Object> statusAndReason(long lot) {
        Map<String, Object> row = jdbc.queryForMap("SELECT status, blocked_reason FROM lots WHERE id = ?", lot);
        return java.util.Arrays.asList(row.get("status"), row.get("blocked_reason"));
    }

    private void orderStatus(long orderId, String status, String received) throws Exception {
        entityManager.clear();
        pharmacist.get("/api/purchase-orders/" + orderId).andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.lines[0].receivedBase").value(received));
    }

    private JsonNode sentOrder(String quantity) throws Exception {
        JsonNode order = read(pharmacist.post("/api/purchase-orders", "{\"supplierId\":" + supplier + ",\"lines\":["
                + "{\"productId\":" + tablets + ",\"quantity\":\"" + quantity + "\",\"unitPrice\":\"0.3\"}]}")
                .andExpect(status().isCreated()));
        long id = order.get("id").asLong();
        pharmacist.post("/api/purchase-orders/" + id + "/validate", "").andExpect(status().isOk());
        return read(pharmacist.post("/api/purchase-orders/" + id + "/send", "").andExpect(status().isOk()));
    }

    private ResultActions prefill() throws Exception {
        return pharmacist.get("/api/purchase-orders/prefill?supplierId=" + supplier + "&productId=" + tablets);
    }

    private JsonNode receive(String body) throws Exception {
        return read(pharmacist.post("/api/goods-receipts", body).andExpect(status().isCreated()));
    }

    private ResultActions cancel(long id, String reason) throws Exception {
        return pharmacist.post("/api/goods-receipts/" + id + "/cancel",
                reason == null ? "{}" : "{\"reason\":\"" + reason + "\"}");
    }

    private long receiptOf(long lot) {
        return jdbc.queryForObject("SELECT receipt_id FROM goods_receipt_lines WHERE lot_id = ?", Long.class, lot);
    }

    private String cost(long lot) {
        entityManager.clear();
        return jdbc.queryForObject("SELECT CAST(unit_cost AS CHAR) FROM lots WHERE id = ?", String.class, lot);
    }

    private long otherSupplier() {
        jdbc.update("INSERT INTO suppliers (name, created_at, updated_at) VALUES ('First lot supplier', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        return jdbc.queryForObject("SELECT id FROM suppliers WHERE name = 'First lot supplier'", Long.class);
    }

    private String free(String lines) {
        return "{\"supplierId\":" + supplier + ",\"lines\":[" + lines + "]}";
    }

    private String onOrder(long orderId, String lines) {
        return "{\"purchaseOrderId\":" + orderId + ",\"lines\":[" + lines + "]}";
    }

    private String line(Long poLine, String lotNumber, String quantity, String cost, boolean conforming) {
        return "{\"poLineId\":" + poLine + ",\"productId\":" + tablets + ",\"quantity\":\"" + quantity
                + "\",\"lotNumber\":\"" + lotNumber + "\",\"expiryDate\":\"2027-06-30\",\"unitCost\":\"" + cost
                + "\",\"conforming\":" + conforming + "}";
    }

    private static long lotOf(JsonNode receipt) {
        return lotOf(receipt, 0);
    }

    private static long lotOf(JsonNode receipt, int line) {
        return receipt.get("lines").get(line).get("lotId").asLong();
    }

    private static JsonNode read(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
