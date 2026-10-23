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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Manual adjustments (W7, ADR-085, FR-STOCK-005): breakage, expiry, theft or internal use, a quantity > 0 in base
 * units, a mandatory reason (BR-STOCK-006/008); one negative movement through StockService, never below zero
 * (BR-STOCK-001), allowed on blocked, recalled and expired lots, audited STOCK_CORRECTION with old and new quantity.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class StockAdjustmentsTest {

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
    private long tablets;
    private long lot;

    @BeforeEach
    void lot() throws Exception {
        clock.reset();
        user = new TestUsers(jdbc, encoder).create("Pharmacist");
        pharmacist = ApiClient.create(context, "198.51.100.200");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
        lots = new TestLots(jdbc);
        tablets = lots.product("ADJ-TAB", "Amoxival 500");
        lot = lots.lot(tablets, "L-ADJ", TODAY.plusDays(200), "12");
    }

    @AfterEach
    void ledgerMatches() {
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
    }

    @Test
    void anAdjustmentRemovesStockAndIsAuditedWithOldAndNewQuantity() throws Exception {
        adjust(lot, "adjustment_breakage", "3", "Broken blister").andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("adjustment_breakage"))
                .andExpect(jsonPath("$.quantity").value("-3.000"))
                .andExpect(jsonPath("$.reason").value("Broken blister"))
                .andExpect(jsonPath("$.lot.id").value(lot))
                .andExpect(jsonPath("$.product.id").value(tablets))
                .andExpect(jsonPath("$.user.id").value(user.id()))
                .andExpect(jsonPath("$.referenceType").value("adjustment"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-20T08:00:00Z"));

        assertThat(balance(lot)).isEqualTo("9.000");
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT user_id, old_values->>'$.quantity' AS old_qty, new_values->>'$.quantity' AS new_qty,
                       new_values->>'$.type' AS type, new_values->>'$.reason' AS reason
                FROM audit_log WHERE action = 'STOCK_CORRECTION' AND entity = 'lots' AND entity_id = ?""", lot);
        assertThat(audit).containsEntry("user_id", user.id()).containsEntry("old_qty", "12.000")
                .containsEntry("new_qty", "9.000").containsEntry("type", "adjustment_breakage")
                .containsEntry("reason", "Broken blister");
    }

    @Test
    void unsellableLotsCanStillBeAdjusted() throws Exception {
        long blocked = lots.lot(tablets, "L-BLK", TODAY.plusDays(100), "2", "blocked");
        long recalled = lots.lot(tablets, "L-RCL", TODAY.plusDays(100), "2", "recalled");
        long expired = lots.lot(tablets, "L-EXP", TODAY.minusDays(5), "2");

        adjust(blocked, "adjustment_internal_use", "1", "Sample").andExpect(status().isCreated());
        adjust(recalled, "adjustment_expiry", "2", "Recalled stock destroyed").andExpect(status().isCreated());
        adjust(expired, "adjustment_expiry", "2", "Expired stock destroyed").andExpect(status().isCreated());

        assertThat(balance(recalled)).isEqualTo("0.000");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(blocked, recalled, expired));
    }

    @Test
    void moreThanTheBalanceIsRefusedAndNothingChanges() throws Exception {
        adjust(lot, "adjustment_theft", "13", "Theft").andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"))
                .andExpect(jsonPath("$.context.available").value("12.000"));

        assertThat(balance(lot)).isEqualTo("12.000");
        assertThat(audits()).isZero();
    }

    @Test
    void theReasonIsMandatory() throws Exception {
        adjust(lot, "adjustment_theft", "1", "  ").andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("ADJUSTMENT_REASON_REQUIRED"));
        pharmacist.post("/api/inventory/adjustments", "{\"lotId\":" + lot + ",\"type\":\"adjustment_theft\",\"quantity\":\"1\"}")
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("ADJUSTMENT_REASON_REQUIRED"));
        assertFieldError(adjust(lot, "adjustment_theft", "1", "x".repeat(501)), "reason", "SIZE");
        assertThat(audits()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = { "sale", "opening_stock", "adjustment_inventory", "purchase_receipt", "loss" })
    void onlyTheFourManualTypesAreAccepted(String type) throws Exception {
        assertFieldError(adjust(lot, type, "1", "Reason"), "type", "UNKNOWN");
    }

    @Test
    void theQuantityIsPositiveInBaseUnitsAndWholeForANonDivisibleProduct() throws Exception {
        assertFieldError(adjust(lot, "adjustment_breakage", "0", "Reason"), "quantity", "POSITIVE");
        assertFieldError(adjust(lot, "adjustment_breakage", "-1", "Reason"), "quantity", "POSITIVE");
        assertFieldError(adjust(lot, "adjustment_breakage", "1.5", "Reason"), "quantity", "WHOLE_NUMBER");
        jdbc.update("UPDATE products SET is_divisible = TRUE WHERE id = ?", tablets);
        entityManager.clear();
        assertFieldError(adjust(lot, "adjustment_breakage", "0.0001", "Reason"), "quantity", "SCALE");
    }

    @Test
    void anUnknownLotIsNotFound() throws Exception {
        adjust(999_999, "adjustment_breakage", "1", "Reason").andExpect(status().isNotFound());
    }

    private ResultActions adjust(long lotId, String type, String quantity, String reason) throws Exception {
        return pharmacist.post("/api/inventory/adjustments", "{\"lotId\":" + lotId + ",\"type\":\"" + type
                + "\",\"quantity\":\"" + quantity + "\",\"reason\":\"" + reason + "\"}");
    }

    private static void assertFieldError(ResultActions actions, String field, String code) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code));
    }

    private String balance(long lotId) {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", String.class, lotId);
    }

    private int audits() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'STOCK_CORRECTION' AND entity_id = ?",
                Integer.class, lot);
    }
}
