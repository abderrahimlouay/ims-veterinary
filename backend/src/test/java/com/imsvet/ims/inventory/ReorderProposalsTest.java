package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
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

/**
 * Reorder proposals (W10, BR-STOCK-010): a product not archived with a reorder point > 0 and sellable quantity at
 * or below it gets one pending proposal for max − sellable (ADR-076, ADR-077); without a max stock it is reported
 * thresholds incomplete; each generation updates the pending proposal in place; a dismissed product gets none until
 * a generation sees its sellable stock above the reorder point (ADR-079).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ReorderProposalsTest {

    private static final LocalDate EXPIRY = LocalDate.of(2027, 5, 31);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    private TestLots lots;
    private TestUser user;
    private ApiClient pharmacist;

    @BeforeEach
    void login() throws Exception {
        clock.reset();
        lots = new TestLots(jdbc);
        user = new TestUsers(jdbc, encoder).create("Pharmacist");
        pharmacist = ApiClient.create(context, "198.51.100.242");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void aProductAtOrBelowItsReorderPointGetsMaxMinusSellable() throws Exception {
        long low = product("RO-LOW", "10", "50");
        lots.lot(low, "A", EXPIRY, "4");
        lots.lot(low, "EXPIRED", LocalDate.of(2026, 9, 30), "20");
        lots.lot(low, "BLOCKED", EXPIRY, "20", "blocked");
        long atPoint = product("RO-AT", "10", "30");
        lots.lot(atPoint, "A", EXPIRY, "10");
        long above = product("RO-ABOVE", "10", "50");
        lots.lot(above, "A", EXPIRY, "11");
        long noPoint = product("RO-NOPOINT", "0", "50");
        long archived = product("RO-ARCH", "10", "50");
        jdbc.update("UPDATE products SET is_archived = TRUE WHERE id = ?", archived);

        generate().andExpect(status().isOk());

        assertThat(pending(low)).containsEntry("current_qty", "4.000").containsEntry("suggested_qty", "46.000");
        assertThat(pending(atPoint)).containsEntry("current_qty", "10.000").containsEntry("suggested_qty", "20.000");
        assertThat(proposals(above)).isZero();
        assertThat(proposals(noPoint)).isZero();
        assertThat(proposals(archived)).isZero();
        pharmacist.get("/api/inventory/reorder-proposals?status=pending").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.product.id == " + low + ")].suggestedQty").value(hasItem("46.000")))
                .andExpect(jsonPath("$.content[?(@.product.id == " + low + ")].reorderPoint").value(hasItem("10.000")))
                .andExpect(jsonPath("$.content[?(@.product.id == " + low + ")].maxStock").value(hasItem("50.000")))
                .andExpect(jsonPath("$.content[?(@.product.id == " + low + ")].status").value(hasItem("pending")));
    }

    @Test
    void withoutAMaxStockTheProductIsReportedNotProposed() throws Exception {
        long noMax = product("RO-NOMAX", "10", null);
        long maxBelowStock = product("RO-MAXLOW", "10", "5");
        lots.lot(maxBelowStock, "A", EXPIRY, "6");

        generate().andExpect(status().isOk())
                .andExpect(jsonPath("$.thresholdsIncomplete[*].id").value(hasItem((int) noMax)))
                .andExpect(jsonPath("$.thresholdsIncomplete[?(@.id == " + noMax + ")].sku").value(hasItem("RO-NOMAX")))
                .andExpect(jsonPath("$.thresholdsIncomplete[*].id").value(hasItem((int) maxBelowStock)));

        assertThat(proposals(noMax)).isZero();
        assertThat(proposals(maxBelowStock)).isZero();
    }

    @Test
    void eachGenerationUpdatesTheOnePendingProposal() throws Exception {
        long product = product("RO-UPD", "10", "50");
        long lot = lots.lot(product, "A", EXPIRY, "8");
        generate().andExpect(jsonPath("$.created").value(greaterThanOrEqualTo(1)));
        long id = proposalId(product);

        jdbc.update("UPDATE lots SET quantity_on_hand = 3 WHERE id = ?", lot);
        clock.advance(Duration.ofHours(1));
        generate().andExpect(status().isOk());

        assertThat(proposals(product)).isEqualTo(1);
        assertThat(proposalId(product)).isEqualTo(id);
        assertThat(pending(product)).containsEntry("current_qty", "3.000").containsEntry("suggested_qty", "47.000");
        assertThat(jdbc.queryForObject("SELECT updated_at > created_at FROM reorder_proposals WHERE id = ?",
                Boolean.class, id)).isTrue();
    }

    @Test
    void aDismissedProductWaitsUntilItsStockRecovers() throws Exception {
        long product = product("RO-DIS", "10", "50");
        long lot = lots.lot(product, "A", EXPIRY, "5");
        generate();
        long id = proposalId(product);

        pharmacist.post("/api/inventory/reorder-proposals/" + id + "/dismiss", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("dismissed"))
                .andExpect(jsonPath("$.dismissedBy.id").value(user.id()))
                .andExpect(jsonPath("$.dismissedAt").value("2026-10-20T08:00:00Z"));
        generate();
        assertThat(pendingCount(product)).isZero();

        jdbc.update("UPDATE lots SET quantity_on_hand = 11 WHERE id = ?", lot);
        generate();
        assertThat(pendingCount(product)).isZero();
        assertThat(jdbc.queryForObject("SELECT suppressed FROM reorder_proposals WHERE id = ?", Boolean.class, id))
                .isFalse();

        jdbc.update("UPDATE lots SET quantity_on_hand = 2 WHERE id = ?", lot);
        generate();
        assertThat(pending(product)).containsEntry("suggested_qty", "48.000");
    }

    @Test
    void onlyAPendingProposalIsDismissed() throws Exception {
        long product = product("RO-TWICE", "10", "50");
        lots.lot(product, "A", EXPIRY, "5");
        generate();
        long id = proposalId(product);
        pharmacist.post("/api/inventory/reorder-proposals/" + id + "/dismiss", "").andExpect(status().isOk());

        pharmacist.post("/api/inventory/reorder-proposals/" + id + "/dismiss", "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        pharmacist.post("/api/inventory/reorder-proposals/999999/dismiss", "").andExpect(status().isNotFound());
        pharmacist.get("/api/inventory/reorder-proposals?status=pending")
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem((int) id))));
        pharmacist.get("/api/inventory/reorder-proposals?status=dismissed")
                .andExpect(jsonPath("$.content[*].id").value(hasItem((int) id)));
        pharmacist.get("/api/inventory/reorder-proposals?status=late").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status"))
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN"));
    }

    @Test
    void aGenerationIsRecordedWithWhoAsked() throws Exception {
        generate().andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT triggered_by FROM reorder_runs ORDER BY id DESC LIMIT 1", Long.class))
                .isEqualTo(user.id());
    }

    /** {@code max} null: no max stock. */
    private long product(String sku, String reorderPoint, String max) {
        long id = lots.product(sku, sku);
        jdbc.update("UPDATE products SET reorder_point = ?, max_stock = ? WHERE id = ?", reorderPoint, max, id);
        return id;
    }

    private ResultActions generate() throws Exception {
        return pharmacist.post("/api/inventory/reorder-proposals/generate", "");
    }

    private Map<String, Object> pending(long product) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT CAST(current_qty AS CHAR) AS current_qty, CAST(suggested_qty AS CHAR) AS suggested_qty
                FROM reorder_proposals WHERE product_id = ? AND status = 'pending'""", product);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private int proposals(long product) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reorder_proposals WHERE product_id = ?", Integer.class, product);
    }

    private int pendingCount(long product) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reorder_proposals WHERE product_id = ? AND status = 'pending'",
                Integer.class, product);
    }

    private long proposalId(long product) {
        return jdbc.queryForObject("SELECT id FROM reorder_proposals WHERE product_id = ? AND status = 'pending'",
                Long.class, product);
    }
}
