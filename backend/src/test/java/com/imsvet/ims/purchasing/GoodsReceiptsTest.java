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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Goods receipts (FR-PURCH-005, W1b, api.md §5.4): lot found or created with number and expiry (BR-LOT-001/002,
 * BR-PURCH-005), weighted-average cost (ADR-098), supplier on a new lot only (ADR-105), a purchase_receipt movement
 * with ledger = balance (BR-STOCK-002), order progress and over-receipt (BR-PURCH-004, ADR-096), conformity and lot
 * blocking (BR-PURCH-006, BR-LOT-012, ADR-095), price history (BR-PURCH-008, ADR-100), idempotent confirmation
 * (BR-PURCH-013, ADR-104), the lots' previous state kept per line (ADR-106). The business date is 2026-10-20.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class GoodsReceiptsTest {

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

    @Autowired
    private EntityManager entityManager;

    private TestLots lots;
    private TestUser user;
    private ApiClient pharmacist;
    private long supplier;
    private long tablets;
    private long syrup;
    private long box;

    @BeforeEach
    void data() throws Exception {
        clock.reset();
        lots = new TestLots(jdbc);
        supplier = supplier("GR Supplier");
        tablets = lots.product("GR-TAB", "Receipt tablets");
        syrup = lots.product("GR-SYR", "Receipt syrup");
        jdbc.update("UPDATE products SET is_divisible = TRUE, base_unit = 'ml' WHERE id = ?", syrup);
        jdbc.update("INSERT INTO product_units (product_id, name, factor_to_base) VALUES (?, 'Box', 30)", tablets);
        box = jdbc.queryForObject("SELECT id FROM product_units WHERE product_id = ? AND name = 'Box'", Long.class, tablets);
        user = new TestUsers(jdbc, encoder).create("Pharmacist");
        pharmacist = ApiClient.create(context, "198.51.100.245");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void aFreeReceiptCreatesTheLotWithItsSupplierAndPostsTheMovement() throws Exception {
        JsonNode receipt = read(receive("{\"supplierId\":" + supplier + ",\"supplierDocRef\":\"BL-1\",\"lines\":["
                + line(null, tablets, box, "2", "L-NEW", "2027-06-30", "0.3500", true) + "]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("confirmed"))
                .andExpect(jsonPath("$.supplier.name").value("GR Supplier"))
                .andExpect(jsonPath("$.purchaseOrder").isEmpty())
                .andExpect(jsonPath("$.supplierDocRef").value("BL-1"))
                .andExpect(jsonPath("$.receivedAt").value("2026-10-20T08:00:00Z"))
                .andExpect(jsonPath("$.lines[0].lotNumber").value("L-NEW"))
                .andExpect(jsonPath("$.lines[0].expiryDate").value("2027-06-30"))
                .andExpect(jsonPath("$.lines[0].quantityBase").value("60.000"))
                .andExpect(jsonPath("$.lines[0].unitCost").value("0.3500"))
                .andExpect(jsonPath("$.lines[0].conforming").value(true))
                .andExpect(jsonPath("$.lines[0].lotBlocked").value(false)));

        assertThat(receipt.get("number").asString()).matches("GR-2026-\\d{6}");
        long lot = receipt.get("lines").get(0).get("lotId").asLong();
        assertThat(jdbc.queryForMap("""
                SELECT CAST(quantity_on_hand AS CHAR) AS qty, CAST(unit_cost AS CHAR) AS cost, supplier_id, status
                FROM lots WHERE id = ?""", lot)).containsAllEntriesOf(Map.of("qty", "60.000", "cost", "0.3500",
                "supplier_id", supplier, "status", "active"));
        assertThat(jdbc.queryForMap("""
                SELECT movement_type, reference_type, reference_id, CAST(quantity AS CHAR) AS qty FROM stock_movements
                WHERE id = ?""", receipt.get("lines").get(0).get("movementId").asLong()))
                .containsAllEntriesOf(Map.of("movement_type", "purchase_receipt", "reference_type", "goods_receipt",
                        "reference_id", receipt.get("id").asLong(), "qty", "60.000"));
        assertThat(jdbc.queryForMap("""
                SELECT lot_created, lot_cost_before, lot_status_before, lot_blocked FROM goods_receipt_lines
                WHERE receipt_id = ?""", receipt.get("id").asLong()))
                .containsEntry("lot_created", true).containsEntry("lot_blocked", false)
                .containsEntry("lot_cost_before", null).containsEntry("lot_status_before", null);
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
        pharmacist.get("/api/goods-receipts/" + receipt.get("id").asLong()).andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(receipt.get("number").asString()))
                .andExpect(jsonPath("$.receivedBy.fullName").isNotEmpty());
    }

    @Test
    void anExistingLotAddsUpAveragesItsCostAndKeepsItsFirstSupplier() throws Exception {
        long other = supplier("First supplier");
        long lot = lots.lot(tablets, "L-OLD", LocalDate.of(2027, 5, 31), "10");
        jdbc.update("UPDATE lots SET unit_cost = 0.3000, supplier_id = ? WHERE id = ?", other, lot);

        JsonNode receipt = read(receive(free(line(null, tablets, null, "5", "l-old", "2027-05-31", "0.3500", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].lotId").value(lot)));

        assertThat(jdbc.queryForMap("""
                SELECT CAST(quantity_on_hand AS CHAR) AS qty, CAST(unit_cost AS CHAR) AS cost, supplier_id
                FROM lots WHERE id = ?""", lot)).containsAllEntriesOf(Map.of("qty", "15.000", "cost", "0.3167",
                "supplier_id", other));
        assertThat(jdbc.queryForMap("""
                SELECT lot_created, CAST(lot_cost_before AS CHAR) AS cost, lot_status_before FROM goods_receipt_lines
                WHERE receipt_id = ?""", receipt.get("id").asLong()))
                .containsAllEntriesOf(Map.of("lot_created", false, "cost", "0.3000", "lot_status_before", "active"));
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
    }

    @Test
    void anEmptyLotTakesTheReceiptCost() throws Exception {
        long lot = lots.lot(tablets, "L-ZERO", LocalDate.of(2027, 5, 31), "0");

        receive(free(line(null, tablets, null, "4", "L-ZERO", "2027-05-31", "0.4100", true)))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT CAST(unit_cost AS CHAR) FROM lots WHERE id = ?", String.class, lot))
                .isEqualTo("0.4100");
    }

    @Test
    void anotherExpiryOrARecalledLotIsRefusedAndABlockedLotStaysBlocked() throws Exception {
        lots.lot(tablets, "L-EXP", LocalDate.of(2027, 5, 31), "3");
        receive(free(line(null, tablets, null, "1", "L-EXP", "2027-06-30", "0.3", true))).andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("LOT_EXPIRY_MISMATCH"));

        lots.lot(tablets, "L-RCL", LocalDate.of(2027, 5, 31), "3", "recalled");
        receive(free(line(null, tablets, null, "1", "L-RCL", "2027-05-31", "0.3", true))).andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("LOT_RECALLED"));

        long blocked = lots.lot(tablets, "L-BLK", LocalDate.of(2027, 5, 31), "3", "blocked");
        receive(free(line(null, tablets, null, "2", "L-BLK", "2027-05-31", "0.5", true))).andExpect(status().isCreated());
        assertThat(jdbc.queryForMap("SELECT CAST(quantity_on_hand AS CHAR) AS qty, status FROM lots WHERE id = ?", blocked))
                .containsAllEntriesOf(Map.of("qty", "5.000", "status", "blocked"));
    }

    @Test
    void anOrderIsReceivedPartlyThenFullyAndNeverBeyondWhatIsOutstanding() throws Exception {
        JsonNode order = sentOrder("{\"productId\":" + tablets + ",\"unitId\":" + box + ",\"quantity\":\"2\","
                + "\"unitPrice\":\"9.0000\",\"discountPct\":\"5\"}");
        long orderId = order.get("id").asLong();
        long poLine = order.get("lines").get(0).get("id").asLong();

        receive(onOrder(orderId, line(poLine, tablets, null, "61", "L-A", "2027-06-30", "0.3", true)))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("RECEIPT_QUANTITY_EXCEEDS_ORDER"))
                .andExpect(jsonPath("$.context.outstandingBase").value("60.000"))
                .andExpect(jsonPath("$.context.requestedBase").value("61.000"));
        receive(onOrder(orderId, line(poLine, tablets, null, "21", "L-A", "2027-06-30", "0.3", true) + ","
                + line(poLine, tablets, null, "19", "L-B", "2027-07-31", "0.3", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purchaseOrder.status").value("partially_received"))
                .andExpect(jsonPath("$.purchaseOrder.number").value(order.get("number").asString()));
        pharmacist.get("/api/purchase-orders/" + orderId)
                .andExpect(jsonPath("$.lines[0].receivedBase").value("40.000"))
                .andExpect(jsonPath("$.lines[0].outstandingBase").value("20.000"))
                .andExpect(jsonPath("$.receipts.length()").value(1));
        receive(onOrder(orderId, line(poLine, tablets, null, "21", "L-C", "2027-06-30", "0.3", true)))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("RECEIPT_QUANTITY_EXCEEDS_ORDER"));

        receive(onOrder(orderId, line(poLine, tablets, null, "20", "L-C", "2027-06-30", "0.3", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purchaseOrder.status").value("closed"));
        pharmacist.get("/api/purchase-orders/" + orderId).andExpect(jsonPath("$.status").value("closed"))
                .andExpect(jsonPath("$.closedReason").isEmpty())
                .andExpect(jsonPath("$.lines[0].outstandingBase").value("0.000"))
                .andExpect(jsonPath("$.receipts.length()").value(2));
        receive(onOrder(orderId, line(poLine, tablets, null, "1", "L-D", "2027-06-30", "0.3", true)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void onlySentOrPartiallyReceivedOrdersOfThatSupplierAndTheirProductsAreReceived() throws Exception {
        JsonNode order = sentOrder("{\"productId\":" + tablets + ",\"quantity\":\"10\",\"unitPrice\":\"1\"}");
        long orderId = order.get("id").asLong();
        long poLine = order.get("lines").get(0).get("id").asLong();

        assertFieldError(receive(onOrder(orderId, line(poLine, syrup, null, "1", "L-1", "2027-06-30", "0.3", true))),
                "lines[0].productId", "NOT_ON_ORDER");
        assertFieldError(receive(onOrder(orderId, line(null, tablets, null, "1", "L-1", "2027-06-30", "0.3", true))),
                "lines[0].poLineId", "REQUIRED");
        assertFieldError(receive(free(line(poLine, tablets, null, "1", "L-1", "2027-06-30", "0.3", true))),
                "lines[0].poLineId", "NOT_ON_ORDER");
        long other = supplier("Other GR supplier");
        assertFieldError(receive("{\"supplierId\":" + other + ",\"purchaseOrderId\":" + orderId + ",\"lines\":["
                + line(poLine, tablets, null, "1", "L-1", "2027-06-30", "0.3", true) + "]}"), "supplierId",
                "NOT_ORDER_SUPPLIER");

        for (String state : List.of("draft", "validated", "cancelled", "closed")) {
            jdbc.update("""
                    UPDATE purchase_orders SET status = ?, cancel_reason = IF(? = 'cancelled', 'x', NULL),
                           cancelled_at = IF(? = 'cancelled', UTC_TIMESTAMP(6), NULL) WHERE id = ?""",
                    state, state, state, orderId);
            entityManager.clear();
            receive(onOrder(orderId, line(poLine, tablets, null, "1", "L-1", "2027-06-30", "0.3", true)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"))
                    .andExpect(jsonPath("$.context.status").value(state));
        }
        receive("{\"purchaseOrderId\":999999,\"lines\":[" + line(poLine, tablets, null, "1", "L-1", "2027-06-30",
                "0.3", true) + "]}").andExpect(status().isNotFound());
        assertThat(receipts()).isZero();
    }

    @Test
    void anInactiveSupplierOrAnArchivedProductIsRefused() throws Exception {
        jdbc.update("UPDATE products SET is_archived = TRUE WHERE id = ?", syrup);
        entityManager.clear();
        receive(free(line(null, syrup, null, "1", "L-1", "2027-06-30", "0.3", true))).andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));

        jdbc.update("UPDATE suppliers SET is_active = FALSE WHERE id = ?", supplier);
        entityManager.clear();
        receive(free(line(null, tablets, null, "1", "L-1", "2027-06-30", "0.3", true))).andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("SUPPLIER_INACTIVE"));
    }

    @Test
    void lotNumberExpiryQuantityAndCostAreValidated() throws Exception {
        assertFieldError(receive("{\"supplierId\":" + supplier + ",\"lines\":[]}"), "lines", "REQUIRED");
        assertFieldError(receive(free(line(null, tablets, null, "1", " ", "2027-06-30", "0.3", true))),
                "lines[0].lotNumber", "REQUIRED");
        assertFieldError(receive(free(line(null, tablets, null, "1", "L".repeat(61), "2027-06-30", "0.3", true))),
                "lines[0].lotNumber", "SIZE");
        assertFieldError(receive(free(line(null, tablets, null, "1", "L-1", null, "0.3", true))),
                "lines[0].expiryDate", "REQUIRED");
        assertFieldError(receive(free(line(null, tablets, null, "0", "L-1", "2027-06-30", "0.3", true))),
                "lines[0].quantity", "POSITIVE");
        assertFieldError(receive(free(line(null, tablets, null, "1.5", "L-1", "2027-06-30", "0.3", true))),
                "lines[0].quantity", "WHOLE_NUMBER");
        assertFieldError(receive(free(line(null, tablets, null, "1", "L-1", "2027-06-30", null, true))),
                "lines[0].unitCost", "REQUIRED");
        assertFieldError(receive(free(line(null, tablets, null, "1", "L-1", "2027-06-30", "-0.1", true))),
                "lines[0].unitCost", "NOT_NEGATIVE");
        assertFieldError(receive(free(line(null, tablets, null, "1", "L-1", "2027-06-30", "0.00001", true))),
                "lines[0].unitCost", "SCALE");
        assertFieldError(receive(free(line(null, tablets, null, "1", "L-1", "2027-06-30", "0.3", true) + ","
                + line(null, tablets, null, "2", "l-1", "2027-06-30", "0.3", true))), "lines[1].lotNumber", "DUPLICATE");
        receive(free(line(null, syrup, null, "2.5", "S-1", "2027-06-30", "0.0120", true))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].quantityBase").value("2.500"));
    }

    /**
     * One line per lot (workflows.md W1b): the same lot number for two products is two lots and is received; the same
     * lot twice on one order line is refused on the second line, telling the user to add the quantities on one line.
     * Two order lines never have the same product (ADR-105 (e)).
     */
    @Test
    void aLotIsReceivedOnOneLineButTheSameNumberOfAnotherProductIsAnotherLot() throws Exception {
        receive(free(line(null, tablets, null, "2", "SAME-1", "2027-06-30", "0.3", true) + ","
                + line(null, syrup, null, "2", "SAME-1", "2027-06-30", "0.3", true))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines.length()").value(2));

        JsonNode order = sentOrder("{\"productId\":" + tablets + ",\"quantity\":\"10\",\"unitPrice\":\"1\"}");
        long poLine = order.get("lines").get(0).get("id").asLong();
        receive(onOrder(order.get("id").asLong(), line(poLine, tablets, null, "2", "L-TWICE", "2027-06-30", "0.3", true)
                + "," + line(poLine, tablets, null, "3", "l-twice", "2027-06-30", "0.3", true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lines[1].lotNumber"))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"))
                .andExpect(jsonPath("$.errors[0].message").value(
                        "This lot is already on another line of this receipt: add the quantities on one line."));
    }

    /** BR-LOT-012, ADR-095: an expired conforming line is refused; marked non-conforming it enters a blocked lot. */
    @Test
    void anExpiredLineIsReceivedOnlyAsNonConforming() throws Exception {
        assertFieldError(receive(free(line(null, tablets, null, "3", "L-OLD", "2026-10-19", "0.3", true))),
                "lines[0].expiryDate", "EXPIRED");
        receive(free(line(null, tablets, null, "3", "L-TODAY", "2026-10-20", "0.3", true))).andExpect(status().isCreated());

        JsonNode receipt = read(receive(free(line(null, tablets, null, "3", "L-OLD", "2026-10-19", "0.3", false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].conforming").value(false))
                .andExpect(jsonPath("$.lines[0].lotBlocked").value(true)));
        assertBlockedBy(receipt);
    }

    /** BR-PURCH-006, ADR-095: blocking a lot that already has stock needs an explicit confirmation. */
    @Test
    void aNonConformingLineBlocksItsLotAndALotWithStockOnlyWhenConfirmed() throws Exception {
        JsonNode created = read(receive(free(line(null, tablets, null, "3", "L-NC", "2027-06-30", "0.3", false)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.lines[0].lotBlocked").value(true)));
        assertBlockedBy(created);

        long stocked = lots.lot(tablets, "L-STK", LocalDate.of(2027, 5, 31), "12");
        String body = free(line(null, tablets, null, "2", "L-STK", "2027-05-31", "0.5", false));
        receive(body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOT_BLOCK_CONFIRMATION_REQUIRED"))
                .andExpect(jsonPath("$.context.lots[0].lotId").value(stocked))
                .andExpect(jsonPath("$.context.lots[0].lotNumber").value("L-STK"))
                .andExpect(jsonPath("$.context.lots[0].quantityInStock").value("12.000"));
        assertThat(jdbc.queryForObject("SELECT status FROM lots WHERE id = ?", String.class, stocked)).isEqualTo("active");

        JsonNode confirmed = read(receive(body.replace("{\"supplierId\"", "{\"confirmLotBlocking\":true,\"supplierId\""))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.lines[0].lotBlocked").value(true)));
        assertBlockedBy(confirmed);
        assertThat(jdbc.queryForMap("""
                SELECT lot_status_before, lot_blocked FROM goods_receipt_lines WHERE receipt_id = ?""",
                confirmed.get("id").asLong())).containsAllEntriesOf(Map.of("lot_status_before", "active",
                "lot_blocked", true));
        assertThat(jdbc.queryForObject("SELECT CAST(quantity_on_hand AS CHAR) FROM lots WHERE id = ?", String.class,
                stocked)).isEqualTo("14.000");

        long alreadyBlocked = lots.lot(tablets, "L-ABK", LocalDate.of(2027, 5, 31), "4", "blocked");
        receive(free(line(null, tablets, null, "1", "L-ABK", "2027-05-31", "0.5", false))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].lotBlocked").value(false));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'LOT_BLOCK' AND entity_id = ?",
                Integer.class, alreadyBlocked)).isZero();
    }

    /** BR-PURCH-008, ADR-100: an entry when price or discount differs from the latest one; the order's discount. */
    @Test
    void aReceiptAppendsAPriceEntryOnlyWhenPriceOrDiscountChanges() throws Exception {
        JsonNode order = sentOrder("{\"productId\":" + tablets + ",\"quantity\":\"30\",\"unitPrice\":\"0.3\","
                + "\"discountPct\":\"5\"}");
        long orderId = order.get("id").asLong();
        long poLine = order.get("lines").get(0).get("id").asLong();

        JsonNode first = read(receive(onOrder(orderId, line(poLine, tablets, null, "10", "P-1", "2027-06-30", "0.3000",
                true))).andExpect(status().isCreated()));
        receive(onOrder(orderId, line(poLine, tablets, null, "10", "P-2", "2027-06-30", "0.3", true)))
                .andExpect(status().isCreated());
        JsonNode third = read(receive(onOrder(orderId, line(poLine, tablets, null, "10", "P-3", "2027-06-30", "0.3100",
                true))).andExpect(status().isCreated()));
        long fourth = id(receive(free(line(null, tablets, null, "1", "P-4", "2027-06-30", "0.3100", true)))
                .andExpect(status().isCreated()));

        assertThat(jdbc.queryForList("""
                SELECT CAST(unit_price AS CHAR) AS price, CAST(discount_pct AS CHAR) AS discount, receipt_id,
                       valid_from, cancelled
                FROM supplier_price_history WHERE supplier_id = ? AND product_id = ? ORDER BY id""", supplier, tablets))
                .extracting(r -> List.of(r.get("price"), r.get("discount"), r.get("receipt_id"),
                        r.get("valid_from").toString(), r.get("cancelled")))
                .containsExactly(List.of("0.3000", "5.00", first.get("id").asLong(), "2026-10-20", false),
                        List.of("0.3100", "5.00", third.get("id").asLong(), "2026-10-20", false),
                        List.of("0.3100", "0.00", fourth, "2026-10-20", false));
        pharmacist.get("/api/suppliers/" + supplier + "/prices?productId=" + tablets)
                .andExpect(jsonPath("$.content[0].receiptId").value(fourth))
                .andExpect(jsonPath("$.content[0].cancelled").value(false));
    }

    /** BR-PURCH-013, ADR-104: a repeat returns the first receipt and posts nothing twice. */
    @Test
    void theSameIdempotencyKeyReturnsTheFirstReceipt() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = free(line(null, tablets, null, "5", "L-IDEM", "2027-06-30", "0.3", true));
        JsonNode first = read(receive(body, key).andExpect(status().isCreated()));

        JsonNode again = read(receive(body, key).andExpect(status().isCreated()));

        assertThat(again.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(again.get("number").asString()).isEqualTo(first.get("number").asString());
        long lot = first.get("lines").get(0).get("lotId").asLong();
        assertThat(jdbc.queryForObject("SELECT CAST(quantity_on_hand AS CHAR) FROM lots WHERE id = ?", String.class, lot))
                .isEqualTo("5.000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE lot_id = ?", Integer.class, lot))
                .isEqualTo(1);
        receive(body.replace("\"5\"", "\"6\""), key).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertFieldError(receive(body, "not-a-uuid"), "Idempotency-Key", "PATTERN");
        assertThat(receipts()).isEqualTo(1);
    }

    @Test
    void receiptsAreListedNewestFirstWithFilters() throws Exception {
        long first = id(receive(free(line(null, tablets, null, "1", "L-L1", "2027-06-30", "0.3", true))));
        clock.set(TestClock.START.plusSeconds(86_400));
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
        long second = id(receive(free(line(null, tablets, null, "1", "L-L2", "2027-06-30", "0.3", true))));

        pharmacist.get("/api/goods-receipts?supplierId=" + supplier).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(second))
                .andExpect(jsonPath("$.content[1].id").value(first))
                .andExpect(jsonPath("$.content[0].status").value("confirmed"));
        pharmacist.get("/api/goods-receipts?supplierId=" + supplier + "&from=2026-10-21&to=2026-10-21")
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(second));
        pharmacist.get("/api/goods-receipts?status=bogus").andExpect(status().isBadRequest());
    }

    private void assertBlockedBy(JsonNode receipt) {
        long lot = receipt.get("lines").get(0).get("lotId").asLong();
        String reason = "Non-conforming at receipt " + receipt.get("number").asString();
        assertThat(jdbc.queryForMap("SELECT status, blocked_reason FROM lots WHERE id = ?", lot))
                .containsAllEntriesOf(Map.of("status", "blocked", "blocked_reason", reason));
        assertThat(jdbc.queryForList("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(new_values, '$.reason')) FROM audit_log
                WHERE action = 'LOT_BLOCK' AND entity = 'lots' AND entity_id = ?""", String.class, lot))
                .containsExactly(reason);
    }

    /** A validated and sent order of one line, through the API. */
    private JsonNode sentOrder(String orderLine) throws Exception {
        JsonNode order = read(pharmacist.post("/api/purchase-orders", "{\"supplierId\":" + supplier + ",\"lines\":["
                + orderLine + "]}").andExpect(status().isCreated()));
        long id = order.get("id").asLong();
        pharmacist.post("/api/purchase-orders/" + id + "/validate", "").andExpect(status().isOk());
        return read(pharmacist.post("/api/purchase-orders/" + id + "/send", "").andExpect(status().isOk()));
    }

    private String free(String lines) {
        return "{\"supplierId\":" + supplier + ",\"lines\":[" + lines + "]}";
    }

    private String onOrder(long orderId, String lines) {
        return "{\"purchaseOrderId\":" + orderId + ",\"lines\":[" + lines + "]}";
    }

    private static String line(Long poLine, long product, Long unit, String quantity, String lotNumber, String expiry,
            String cost, boolean conforming) {
        return "{\"poLineId\":" + poLine + ",\"productId\":" + product + ",\"unitId\":" + unit + ",\"quantity\":\""
                + quantity + "\",\"lotNumber\":\"" + lotNumber + "\",\"expiryDate\":"
                + (expiry == null ? "null" : "\"" + expiry + "\"") + ",\"unitCost\":"
                + (cost == null ? "null" : "\"" + cost + "\"") + ",\"conforming\":" + conforming + "}";
    }

    private ResultActions receive(String body) throws Exception {
        return receive(body, null);
    }

    private ResultActions receive(String body, String key) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post("/api/goods-receipts")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return pharmacist.perform(request, true);
    }

    private int receipts() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM goods_receipts WHERE supplier_id = ?", Integer.class, supplier);
    }

    private long supplier(String name) {
        jdbc.update("INSERT INTO suppliers (name, created_at, updated_at) VALUES (?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                name);
        return jdbc.queryForObject("SELECT id FROM suppliers WHERE name = ?", Long.class, name);
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
