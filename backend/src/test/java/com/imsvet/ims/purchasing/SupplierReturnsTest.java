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
 * Supplier returns (FR-PURCH-006, BR-PURCH-009, W4, ADR-101): numbered SR, a reason, lots of that supplier or
 * without supplier in any status (active, blocked, recalled, expired), at most their balance, one supplier_return
 * movement per line with ledger = balance, audited RETURN_CREATE; an inactive supplier still takes returns.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SupplierReturnsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    private TestLots lots;
    private ApiClient pharmacist;
    private long supplier;
    private long tablets;

    @BeforeEach
    void data() throws Exception {
        clock.reset();
        lots = new TestLots(jdbc);
        supplier = supplier("Return supplier");
        tablets = lots.product("SR-TAB", "Returned tablets");
        TestUser user = new TestUsers(jdbc, encoder).create("Pharmacist");
        pharmacist = ApiClient.create(context, "198.51.100.247");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void lotsInAnyStatusAreReturnedWithOneMovementEachAndAudited() throws Exception {
        long active = lot("R-ACT", "active", LocalDate.of(2027, 5, 31), supplier);
        long blocked = lot("R-BLK", "blocked", LocalDate.of(2027, 5, 31), supplier);
        long recalled = lot("R-RCL", "recalled", LocalDate.of(2027, 5, 31), null);
        long expired = lot("R-EXP", "active", LocalDate.of(2026, 1, 31), null);

        JsonNode created = read(create("Defective batch", line(active, "4") + "," + line(blocked, "10") + ","
                + line(recalled, "1") + "," + line(expired, "10")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplier.name").value("Return supplier"))
                .andExpect(jsonPath("$.reason").value("Defective batch"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-20T08:00:00Z"))
                .andExpect(jsonPath("$.lines.length()").value(4))
                .andExpect(jsonPath("$.lines[0].lotNumber").value("R-ACT"))
                .andExpect(jsonPath("$.lines[0].quantityBase").value("4.000")));

        assertThat(created.get("number").asString()).matches("SR-2026-\\d{6}");
        assertThat(balances(active, blocked, recalled, expired)).containsExactly("6.000", "0.000", "9.000", "0.000");
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(movement_type, ' ', CAST(quantity AS CHAR), ' ', reference_type) FROM stock_movements
                WHERE reference_id = ? AND reference_type = 'supplier_return' ORDER BY id""", String.class,
                created.get("id").asLong())).containsExactly("supplier_return -4.000 supplier_return",
                "supplier_return -10.000 supplier_return", "supplier_return -1.000 supplier_return",
                "supplier_return -10.000 supplier_return");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(active, blocked, recalled, expired));
        assertThat(jdbc.queryForMap("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(new_values, '$.reason')) AS reason,
                       JSON_LENGTH(JSON_EXTRACT(new_values, '$.lines')) AS line_count
                FROM audit_log WHERE action = 'RETURN_CREATE' AND entity = 'supplier_returns' AND entity_id = ?""",
                created.get("id").asLong())).containsAllEntriesOf(Map.of("reason", "Defective batch", "line_count", 4L));
        pharmacist.get("/api/returns/supplier/" + created.get("id").asLong()).andExpect(status().isOk())
                .andExpect(jsonPath("$.createdBy.fullName").isNotEmpty());
        pharmacist.get("/api/returns/supplier?supplierId=" + supplier).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].number").value(created.get("number").asString()));
    }

    @Test
    void moreThanTheBalanceOrALotOfAnotherSupplierIsRefused() throws Exception {
        long lot = lot("R-1", "active", LocalDate.of(2027, 5, 31), supplier);
        long other = lot("R-2", "active", LocalDate.of(2027, 5, 31), supplier("Somebody else"));

        create("Too many", line(lot, "11")).andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"))
                .andExpect(jsonPath("$.context.lotId").value(lot))
                .andExpect(jsonPath("$.context.available").value("10.000"));
        assertFieldError(create("Wrong supplier", line(lot, "1") + "," + line(other, "1")), "lines[1].lotId",
                "OTHER_SUPPLIER");
    }

    @Test
    void reasonLotsAndQuantitiesAreValidated() throws Exception {
        long lot = lot("R-V", "active", LocalDate.of(2027, 5, 31), supplier);
        jdbc.update("INSERT INTO product_units (product_id, name, factor_to_base) VALUES (?, 'Box', 5)", tablets);
        long box = jdbc.queryForObject("SELECT id FROM product_units WHERE product_id = ?", Long.class, tablets);

        assertFieldError(create(" ", line(lot, "1")), "reason", "REQUIRED");
        assertFieldError(create("No lines", ""), "lines", "REQUIRED");
        assertFieldError(create("Unknown", line(999_999, "1")), "lines[0].lotId", "UNKNOWN");
        assertFieldError(create("Twice", line(lot, "1") + "," + line(lot, "1")), "lines[1].lotId", "DUPLICATE");
        assertFieldError(create("Zero", line(lot, "0")), "lines[0].quantity", "POSITIVE");
        assertFieldError(create("Half", line(lot, "0.5")), "lines[0].quantity", "WHOLE_NUMBER");
        pharmacist.post("/api/returns/supplier", "{\"reason\":\"No supplier\",\"lines\":[" + line(lot, "1") + "]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("supplierId"));

        create("By the box", "{\"lotId\":" + lot + ",\"quantity\":\"2\",\"unitId\":" + box + "}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.lines[0].quantityBase").value("10.000"));
        assertThat(balances(lot)).containsExactly("0.000");
    }

    @Test
    void anInactiveSupplierStillTakesReturns() throws Exception {
        long lot = lot("R-IN", "active", LocalDate.of(2027, 5, 31), supplier);
        jdbc.update("UPDATE suppliers SET is_active = FALSE WHERE id = ?", supplier);

        create("Closing the account", line(lot, "2")).andExpect(status().isCreated());
    }

    private long lot(String number, String status, LocalDate expiry, Long lotSupplier) {
        long id = lots.lot(tablets, number, expiry, "10", status);
        jdbc.update("UPDATE lots SET supplier_id = ? WHERE id = ?", lotSupplier, id);
        return id;
    }

    private List<String> balances(long... ids) {
        return java.util.Arrays.stream(ids).mapToObj(id -> jdbc.queryForObject(
                "SELECT CAST(quantity_on_hand AS CHAR) FROM lots WHERE id = ?", String.class, id)).toList();
    }

    private ResultActions create(String reason, String lines) throws Exception {
        return pharmacist.post("/api/returns/supplier", "{\"supplierId\":" + supplier + ",\"reason\":\"" + reason
                + "\",\"lines\":[" + lines + "]}");
    }

    private static String line(long lot, String quantity) {
        return "{\"lotId\":" + lot + ",\"quantity\":\"" + quantity + "\"}";
    }

    private long supplier(String name) {
        jdbc.update("INSERT INTO suppliers (name, created_at, updated_at) VALUES (?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                name);
        return jdbc.queryForObject("SELECT id FROM suppliers WHERE name = ?", Long.class, name);
    }

    private static JsonNode read(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static void assertFieldError(ResultActions actions, String field, String code) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code));
    }
}
