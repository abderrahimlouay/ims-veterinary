package com.imsvet.ims.purchasing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import jakarta.persistence.EntityManager;
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
 * Purchase orders (FR-PURCH-003/004, BR-PURCH-001…003/011, ADR-093…097, ADR-105): numbered at creation, never by a
 * refused creation; totals per line, HALF_UP, equal to the database's generated columns; client amounts ignored;
 * one line per product; units of the product converted exactly; drafts only editable; every illegal transition
 * refused; cancel and forced close need a reason and are audited; price prefill.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PurchaseOrdersTest {

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

    private ApiClient pharmacist;
    private long supplier;
    private long tablets;
    private long syrup;
    private long box;

    @BeforeEach
    void data() throws Exception {
        clock.reset();
        TestLots lots = new TestLots(jdbc);
        supplier = supplier("PO Supplier");
        tablets = lots.product("PO-TAB", "Order tablets");
        syrup = lots.product("PO-SYR", "Order syrup");
        jdbc.update("UPDATE products SET is_divisible = TRUE, base_unit = 'ml', purchase_price = 0.1234 WHERE id = ?", syrup);
        jdbc.update("UPDATE products SET vat_rate = 19.00, purchase_price = 0.1250 WHERE id = ?", tablets);
        jdbc.update("INSERT INTO product_units (product_id, name, factor_to_base) VALUES (?, 'Box', 30)", tablets);
        box = jdbc.queryForObject("SELECT id FROM product_units WHERE product_id = ? AND name = 'Box'", Long.class, tablets);
        TestUser user = new TestUsers(jdbc, encoder).create("Pharmacist");
        pharmacist = ApiClient.create(context, "198.51.100.244");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void aDraftIsNumberedAtCreationWithServerTotals() throws Exception {
        JsonNode order = read(create("{\"supplierId\":" + supplier + ",\"expectedDate\":\"2026-11-02\",\"totalTtc\":\"999\","
                + "\"lines\":[{\"productId\":" + tablets + ",\"unitId\":" + box + ",\"quantity\":\"2\",\"unitPrice\":\"3.75\","
                + "\"discountPct\":\"10\",\"lineHt\":\"1\"}]}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.orderDate").value("2026-10-20"))
                .andExpect(jsonPath("$.lines[0].quantityBase").value("60.000"))
                .andExpect(jsonPath("$.lines[0].receivedBase").value("0.000"))
                .andExpect(jsonPath("$.lines[0].outstandingBase").value("60.000"))
                .andExpect(jsonPath("$.lines[0].vatRate").value("19.00"))
                .andExpect(jsonPath("$.lines[0].unit.name").value("Box"))
                .andExpect(jsonPath("$.lines[0].lineHt").value("6.75"))
                .andExpect(jsonPath("$.lines[0].lineVat").value("1.28"))
                .andExpect(jsonPath("$.totalHt").value("6.75"))
                .andExpect(jsonPath("$.totalVat").value("1.28"))
                .andExpect(jsonPath("$.totalTtc").value("8.03")));

        assertThat(order.get("number").asString()).matches("PO-2026-\\d{6}");
        JsonNode second = read(create(oneLine(syrup, "1", "1")).andExpect(status().isCreated()));
        assertThat(sequence(second)).isEqualTo(sequence(order) + 1);
        pharmacist.get("/api/purchase-orders/" + order.get("id").asLong()).andExpect(status().isOk())
                .andExpect(jsonPath("$.supplier.name").value("PO Supplier"))
                .andExpect(jsonPath("$.number").value(order.get("number").asString()));
    }

    @Test
    void lineAmountsAreRoundedPerLineLikeTheGeneratedColumns() throws Exception {
        long gel = new TestLots(jdbc).product("PO-GEL", "Gel");
        long drops = new TestLots(jdbc).product("PO-DRP", "Drops");
        jdbc.update("UPDATE products SET vat_rate = 9.00 WHERE id IN (?, ?, ?)", gel, drops, syrup);
        JsonNode order = read(create("{\"supplierId\":" + supplier + ",\"lines\":["
                + "{\"productId\":" + tablets + ",\"quantity\":\"3\",\"unitPrice\":\"0.3350\",\"vatRate\":\"9\"},"
                + "{\"productId\":" + gel + ",\"quantity\":\"1\",\"unitPrice\":\"2.1250\",\"discountPct\":\"10\"},"
                + "{\"productId\":" + drops + ",\"quantity\":\"1\",\"unitPrice\":\"0.5000\"},"
                + "{\"productId\":" + syrup + ",\"quantity\":\"2.5\",\"unitPrice\":\"0.1234\",\"discountPct\":\"2.5\"}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].lineHt").value("1.01"))
                .andExpect(jsonPath("$.lines[0].lineVat").value("0.09"))
                .andExpect(jsonPath("$.lines[1].lineHt").value("1.91"))
                .andExpect(jsonPath("$.lines[2].lineHt").value("0.50"))
                .andExpect(jsonPath("$.lines[2].lineVat").value("0.05"))
                .andExpect(jsonPath("$.lines[3].quantityBase").value("2.500"))
                .andExpect(jsonPath("$.lines[3].lineHt").value("0.30")));

        List<Map<String, Object>> stored = jdbc.queryForList("""
                SELECT CAST(line_ht AS CHAR) AS ht, CAST(line_vat AS CHAR) AS vat FROM purchase_order_lines
                WHERE purchase_order_id = ? ORDER BY id""", order.get("id").asLong());
        for (int i = 0; i < stored.size(); i++) {
            assertThat(stored.get(i).get("ht")).as("line %d ht", i).isEqualTo(order.get("lines").get(i).get("lineHt").asString());
            assertThat(stored.get(i).get("vat")).as("line %d vat", i).isEqualTo(order.get("lines").get(i).get("lineVat").asString());
        }
        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(CAST(o.total_ht AS CHAR), '/', CAST(o.total_vat AS CHAR)) FROM purchase_orders o
                WHERE o.id = ? AND o.total_ht = (SELECT SUM(line_ht) FROM purchase_order_lines WHERE purchase_order_id = o.id)
                  AND o.total_vat = (SELECT SUM(line_vat) FROM purchase_order_lines WHERE purchase_order_id = o.id)""",
                String.class, order.get("id").asLong())).isEqualTo(order.get("totalHt").asString() + "/"
                + order.get("totalVat").asString());
    }

    @Test
    void aRefusedCreationConsumesNoNumber() throws Exception {
        JsonNode first = read(create(oneLine(tablets, "1", "1")).andExpect(status().isCreated()));
        long inactive = supplier("Inactive Supplier");
        jdbc.update("UPDATE suppliers SET is_active = FALSE WHERE id = ?", inactive);

        pharmacist.post("/api/purchase-orders", "{\"supplierId\":" + inactive + ",\"lines\":[{\"productId\":" + tablets
                + ",\"quantity\":\"1\",\"unitPrice\":\"1\"}]}").andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("SUPPLIER_INACTIVE"));
        assertFieldError(create(oneLine(tablets, "0", "1")), "lines[0].quantity", "POSITIVE");

        JsonNode next = read(create(oneLine(tablets, "1", "1")).andExpect(status().isCreated()));
        assertThat(sequence(next)).isEqualTo(sequence(first) + 1);
    }

    @Test
    void linesAreCheckedAgainstTheCatalogue() throws Exception {
        long other = new TestLots(jdbc).product("PO-OTH", "Other");
        jdbc.update("INSERT INTO product_units (product_id, name, factor_to_base) VALUES (?, 'Pack', 10)", other);
        long pack = jdbc.queryForObject("SELECT id FROM product_units WHERE product_id = ?", Long.class, other);
        jdbc.update("UPDATE products SET is_archived = TRUE WHERE id = ?", other);

        assertFieldError(create("{\"supplierId\":" + supplier + ",\"lines\":[{\"productId\":" + tablets
                + ",\"unitId\":" + pack + ",\"quantity\":\"1\",\"unitPrice\":\"1\"}]}"), "lines[0].unitId", "UNKNOWN");
        assertFieldError(create("{\"supplierId\":" + supplier + ",\"lines\":[{\"productId\":" + tablets
                + ",\"quantity\":\"1\",\"unitPrice\":\"1\"},{\"productId\":" + tablets + ",\"quantity\":\"2\","
                + "\"unitPrice\":\"1\"}]}"), "lines[1].productId", "DUPLICATE");
        assertFieldError(create(oneLine(tablets, "1.5", "1")), "lines[0].quantity", "WHOLE_NUMBER");
        assertFieldError(create(oneLine(tablets, "1", "0.12345")), "lines[0].unitPrice", "SCALE");
        assertFieldError(create("{\"supplierId\":" + supplier + ",\"lines\":[{\"productId\":" + tablets
                + ",\"quantity\":\"1\",\"unitPrice\":\"1\",\"discountPct\":\"101\"}]}"), "lines[0].discountPct", "RANGE");
        assertFieldError(create("{\"supplierId\":" + supplier + ",\"lines\":[]}"), "lines", "REQUIRED");
        assertFieldError(create("{\"supplierId\":999999,\"lines\":[{\"productId\":" + tablets
                + ",\"quantity\":\"1\",\"unitPrice\":\"1\"}]}"), "supplierId", "UNKNOWN");
        create(oneLine(other, "1", "1")).andExpect(status().is(422)).andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));
    }

    @Test
    void validationAndSendingFollowTheStateMachine() throws Exception {
        long id = id(create(oneLine(tablets, "10", "1")));

        action(id, "send", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        action(id, "validate", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("validated"))
                .andExpect(jsonPath("$.validatedAt").value("2026-10-20T08:00:00Z"));
        edit(id, oneLine(tablets, "5", "1"), 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_IMMUTABLE"));
        action(id, "validate", null).andExpect(status().isConflict());
        action(id, "send", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("sent"));
        action(id, "close", "{\"reason\":\"Supplier out of stock\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void everyIllegalTransitionIsRefused() throws Exception {
        Map<String, List<String>> allowed = Map.of(
                "draft", List.of("validate", "cancel"),
                "validated", List.of("send", "cancel"),
                "sent", List.of("cancel"),
                "partially_received", List.of("close"),
                "closed", List.of(),
                "cancelled", List.of());
        for (String from : allowed.keySet()) {
            for (String action : List.of("validate", "send", "cancel", "close")) {
                if (allowed.get(from).contains(action)) {
                    continue;
                }
                long id = id(create(oneLine(tablets, "1", "1")));
                force(id, from);
                action(id, action, "{\"reason\":\"Because\"}").andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
                if (!from.equals("draft")) {
                    edit(id, oneLine(tablets, "2", "1"), 0).andExpect(status().isConflict())
                            .andExpect(jsonPath("$.code").value("DOCUMENT_IMMUTABLE"));
                }
            }
        }
    }

    @Test
    void cancelAndForcedCloseNeedAReasonAndAreAudited() throws Exception {
        long cancelled = id(create(oneLine(tablets, "1", "1")));
        assertFieldError(action(cancelled, "cancel", "{\"reason\":\" \"}"), "reason", "REQUIRED");
        action(cancelled, "cancel", "{\"reason\":\"Ordered twice\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.cancelReason").value("Ordered twice"))
                .andExpect(jsonPath("$.number").isNotEmpty());

        long partial = id(create(oneLine(tablets, "10", "1")));
        force(partial, "partially_received");
        assertFieldError(action(partial, "close", "{}"), "reason", "REQUIRED");
        action(partial, "close", "{\"reason\":\"Rest discontinued\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("closed"))
                .andExpect(jsonPath("$.closedReason").value("Rest discontinued"));

        assertThat(jdbc.queryForList("""
                SELECT action, JSON_UNQUOTE(JSON_EXTRACT(new_values, '$.reason')) AS reason FROM audit_log
                WHERE entity = 'purchase_orders' AND entity_id IN (?, ?) ORDER BY id""", cancelled, partial))
                .containsExactly(Map.of("action", "PO_CANCEL", "reason", "Ordered twice"),
                        Map.of("action", "PO_CLOSE", "reason", "Rest discontinued"));
    }

    @Test
    void aDraftIsReplacedWithItsVersion() throws Exception {
        long id = id(create(oneLine(tablets, "1", "1")));

        edit(id, "{\"supplierId\":" + supplier + ",\"lines\":[{\"productId\":" + syrup + ",\"quantity\":\"4\","
                + "\"unitPrice\":\"0.5\"},{\"productId\":" + tablets + ",\"quantity\":\"2\",\"unitPrice\":\"1\"}]}", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.totalHt").value("4.00"));
        edit(id, oneLine(tablets, "3", "1"), 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void anInactiveSupplierOrArchivedProductBlocksValidation() throws Exception {
        long first = id(create(oneLine(tablets, "1", "1")));
        long second = id(create(oneLine(syrup, "1", "1")));
        jdbc.update("UPDATE suppliers SET is_active = FALSE WHERE id = ?", supplier);
        entityManager.clear();
        action(first, "validate", null).andExpect(status().is(422)).andExpect(jsonPath("$.code").value("SUPPLIER_INACTIVE"));

        jdbc.update("UPDATE suppliers SET is_active = TRUE WHERE id = ?", supplier);
        jdbc.update("UPDATE products SET is_archived = TRUE WHERE id = ?", syrup);
        entityManager.clear();
        action(second, "validate", null).andExpect(status().is(422)).andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));
    }

    @Test
    void aUnitUsedOnAnOrderLineKeepsItsFactor() throws Exception {
        create("{\"supplierId\":" + supplier + ",\"lines\":[{\"productId\":" + tablets + ",\"unitId\":" + box
                + ",\"quantity\":\"1\",\"unitPrice\":\"3.75\"}]}").andExpect(status().isCreated());
        long version = jdbc.queryForObject("SELECT version FROM product_units WHERE id = ?", Long.class, box);

        pharmacist.perform(MockMvcRequestBuilders.put("/api/products/" + tablets + "/units/" + box)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Box\",\"factorToBase\":\"20\","
                        + "\"sellable\":true,\"version\":" + version + "}"), true)
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("UNIT_FACTOR_LOCKED"));
    }

    @Test
    void thePrefillSuggestsTheLatestSupplierPriceElseTheProductPrice() throws Exception {
        prefill(tablets, box).andExpect(status().isOk())
                .andExpect(jsonPath("$.unitPrice").value("3.7500"))
                .andExpect(jsonPath("$.discountPct").value("0.00"))
                .andExpect(jsonPath("$.vatRate").value("19.00"))
                .andExpect(jsonPath("$.source").value("product"));
        price("0.1100", "5", "2026-09-01");
        price("0.1200", "4", "2026-10-15");
        price("0.9999", "0", "2026-12-01");

        prefill(tablets, box).andExpect(jsonPath("$.unitPrice").value("3.6000"))
                .andExpect(jsonPath("$.discountPct").value("4.00"))
                .andExpect(jsonPath("$.source").value("history"));
        prefill(tablets, null).andExpect(jsonPath("$.unitPrice").value("0.1200"));
    }

    private ResultActions create(String body) throws Exception {
        return pharmacist.post("/api/purchase-orders", body);
    }

    private ResultActions edit(long id, String body, long version) throws Exception {
        String versioned = body.substring(0, body.length() - 1) + ",\"version\":" + version + "}";
        return pharmacist.perform(MockMvcRequestBuilders.put("/api/purchase-orders/" + id)
                .contentType(MediaType.APPLICATION_JSON).content(versioned), true);
    }

    private ResultActions action(long id, String action, String body) throws Exception {
        return pharmacist.post("/api/purchase-orders/" + id + "/" + action, body == null ? "" : body);
    }

    private ResultActions prefill(long product, Long unit) throws Exception {
        return pharmacist.get("/api/purchase-orders/prefill?supplierId=" + supplier + "&productId=" + product
                + (unit == null ? "" : "&unitId=" + unit));
    }

    private void price(String unitPrice, String discount, String validFrom) {
        jdbc.update("""
                INSERT INTO supplier_price_history (supplier_id, product_id, unit_price, discount_pct, valid_from,
                                                    created_at, created_by)
                VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), ?)""", supplier, tablets, unitPrice, discount, validFrom,
                new TestLots(jdbc).fixtureUser());
    }

    private String oneLine(long product, String quantity, String unitPrice) {
        return "{\"supplierId\":" + supplier + ",\"lines\":[{\"productId\":" + product + ",\"quantity\":\"" + quantity
                + "\",\"unitPrice\":\"" + unitPrice + "\"}]}";
    }

    /** Puts the order in a state the API reaches only through receipts (slice 5) or cancelling. */
    private void force(long id, String status) {
        jdbc.update("""
                UPDATE purchase_orders SET status = ?,
                       cancel_reason = IF(? = 'cancelled', 'Forced', NULL),
                       cancelled_at = IF(? = 'cancelled', UTC_TIMESTAMP(6), NULL)
                WHERE id = ?""", status, status, status, id);
        entityManager.clear();
    }

    private long supplier(String name) {
        jdbc.update("INSERT INTO suppliers (name, created_at, updated_at) VALUES (?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                name);
        return jdbc.queryForObject("SELECT id FROM suppliers WHERE name = ?", Long.class, name);
    }

    private static int sequence(JsonNode order) {
        String number = order.get("number").asString();
        return Integer.parseInt(number.substring(number.lastIndexOf('-') + 1));
    }

    private static JsonNode read(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static long id(ResultActions actions) throws Exception {
        return read(actions).get("id").asLong();
    }

    private static void assertFieldError(ResultActions actions, String field, String code) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code));
    }
}
