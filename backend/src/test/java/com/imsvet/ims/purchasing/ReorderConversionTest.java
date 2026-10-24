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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reorder proposals and purchase orders (BR-STOCK-010, BR-PURCH-010, ADR-102): the generation subtracts what is
 * ordered and not yet received on validated, sent and partially received orders (drafts not counted), proposes
 * nothing when that covers the need and leaves an existing pending proposal as is; converting selected proposals
 * gives one new draft per supplier with one line per proposal (base unit, suggested quantity, prefilled price) and
 * marks each converted with its supplier, once.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ReorderConversionTest {

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

    @BeforeEach
    void data() throws Exception {
        clock.reset();
        lots = new TestLots(jdbc);
        supplier = supplier("Reorder supplier");
        TestUser user = new TestUsers(jdbc, encoder).create("Pharmacist");
        pharmacist = ApiClient.create(context, "198.51.100.248");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void theGenerationSubtractsOpenOrdersButNotDrafts() throws Exception {
        long ordered = product("RC-ORD");
        long covered = product("RC-COV");
        long left = product("RC-LEFT");
        generate();
        assertThat(suggested(left)).isEqualTo("50.000");

        long draft = order(ordered, "30");
        generate();
        assertThat(suggested(ordered)).as("a draft is not on order").isEqualTo("50.000");

        validate(draft);
        validate(order(covered, "50"));
        validate(order(left, "60"));
        generate();

        assertThat(suggested(ordered)).isEqualTo("20.000");
        assertThat(suggested(covered)).as("the open order covers the need; the pending proposal is left as is")
                .isEqualTo("50.000");
        assertThat(suggested(left)).isEqualTo("50.000");
        long fresh = product("RC-FRESH");
        validate(order(fresh, "50"));
        generate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reorder_proposals WHERE product_id = ?", Integer.class,
                fresh)).as("nothing proposed when the open order covers the need").isZero();
    }

    @Test
    void selectedProposalsBecomeOneDraftPerSupplierWithPrefilledPrices() throws Exception {
        long priced = product("RC-PRICED");
        long plain = product("RC-PLAIN");
        long elsewhere = product("RC-ELSE");
        jdbc.update("UPDATE products SET purchase_price = 0.2500 WHERE id = ?", plain);
        jdbc.update("""
                INSERT INTO supplier_price_history (supplier_id, product_id, unit_price, discount_pct, valid_from,
                                                    created_at, created_by)
                VALUES (?, ?, 0.4000, 5, '2026-10-01', UTC_TIMESTAMP(6), ?)""", supplier, priced, lots.fixtureUser());
        long other = supplier("Second reorder supplier");
        generate();

        JsonNode orders = read(convert("[{\"supplierId\":" + supplier + ",\"proposalIds\":[" + proposal(priced) + ","
                + proposal(plain) + "]},{\"supplierId\":" + other + ",\"proposalIds\":[" + proposal(elsewhere) + "]}]")
                .andExpect(status().isCreated()));

        assertThat(orders.size()).isEqualTo(2);
        JsonNode first = orders.get(0);
        assertThat(first.get("status").asString()).isEqualTo("draft");
        assertThat(first.get("supplier").get("id").asLong()).isEqualTo(supplier);
        assertThat(first.get("number").asString()).matches("PO-2026-\\d{6}");
        assertThat(lines(first)).containsExactly(List.of(priced, "50.000", "0.4000", "5.00"),
                List.of(plain, "50.000", "0.2500", "0.00"));
        assertThat(orders.get(1).get("supplier").get("id").asLong()).isEqualTo(other);
        assertThat(jdbc.queryForList("""
                SELECT status, supplier_id FROM reorder_proposals WHERE product_id IN (?, ?, ?) ORDER BY product_id""",
                priced, plain, elsewhere)).containsExactly(Map.of("status", "converted", "supplier_id", supplier),
                Map.of("status", "converted", "supplier_id", supplier),
                Map.of("status", "converted", "supplier_id", other));
        pharmacist.get("/api/inventory/reorder-proposals?status=converted")
                .andExpect(jsonPath("$.content[0].supplierId").isNumber());

        convert("[{\"supplierId\":" + supplier + ",\"proposalIds\":[" + proposalAnyStatus(priced) + "]}]")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void anInactiveSupplierAnArchivedProductOrADismissedProposalIsRefused() throws Exception {
        long product = product("RC-REF");
        long archived = product("RC-ARCH");
        long dismissed = product("RC-DISM");
        generate();
        long dismissedProposal = proposal(dismissed);
        pharmacist.post("/api/inventory/reorder-proposals/" + dismissedProposal + "/dismiss", "")
                .andExpect(status().isOk());

        convert("[{\"supplierId\":" + supplier + ",\"proposalIds\":[" + dismissedProposal + "]}]")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        jdbc.update("UPDATE products SET is_archived = TRUE WHERE id = ?", archived);
        entityManager.clear();
        convert("[{\"supplierId\":" + supplier + ",\"proposalIds\":[" + proposal(archived) + "]}]")
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));
        long inactive = supplier("Inactive reorder supplier");
        jdbc.update("UPDATE suppliers SET is_active = FALSE WHERE id = ?", inactive);
        convert("[{\"supplierId\":" + inactive + ",\"proposalIds\":[" + proposal(product) + "]}]")
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("SUPPLIER_INACTIVE"));
        convert("[{\"supplierId\":" + supplier + ",\"proposalIds\":[999999]}]").andExpect(status().isNotFound());
    }

    @Test
    void groupsAreValidated() throws Exception {
        long product = product("RC-VAL");
        generate();
        long id = proposal(product);

        assertFieldError(convert("[]"), "groups", "REQUIRED");
        assertFieldError(convert("[{\"proposalIds\":[" + id + "]}]"), "groups[0].supplierId", "REQUIRED");
        assertFieldError(convert("[{\"supplierId\":" + supplier + ",\"proposalIds\":[]}]"), "groups[0].proposalIds",
                "REQUIRED");
        assertFieldError(convert("[{\"supplierId\":" + supplier + ",\"proposalIds\":[" + id + "]},{\"supplierId\":"
                + supplier + ",\"proposalIds\":[1]}]"), "groups[1].supplierId", "DUPLICATE");
        assertFieldError(convert("[{\"supplierId\":" + supplier + ",\"proposalIds\":[" + id + "," + id + "]}]"),
                "groups[0].proposalIds", "DUPLICATE");
    }

    private long product(String sku) {
        long id = lots.product(sku, sku);
        jdbc.update("UPDATE products SET reorder_point = 10, max_stock = 50 WHERE id = ?", id);
        return id;
    }

    private long order(long product, String quantity) throws Exception {
        return read(pharmacist.post("/api/purchase-orders", "{\"supplierId\":" + supplier + ",\"lines\":[{"
                + "\"productId\":" + product + ",\"quantity\":\"" + quantity + "\",\"unitPrice\":\"1\"}]}")
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private void validate(long order) throws Exception {
        pharmacist.post("/api/purchase-orders/" + order + "/validate", "").andExpect(status().isOk());
    }

    private void generate() throws Exception {
        pharmacist.post("/api/inventory/reorder-proposals/generate", "").andExpect(status().isOk());
        entityManager.clear();
    }

    private ResultActions convert(String groups) throws Exception {
        return pharmacist.post("/api/inventory/reorder-proposals/convert", "{\"groups\":" + groups + "}");
    }

    private String suggested(long product) {
        return jdbc.queryForObject("""
                SELECT CAST(suggested_qty AS CHAR) FROM reorder_proposals WHERE product_id = ? AND status = 'pending'""",
                String.class, product);
    }

    private long proposal(long product) {
        return jdbc.queryForObject("SELECT id FROM reorder_proposals WHERE product_id = ? AND status = 'pending'",
                Long.class, product);
    }

    private long proposalAnyStatus(long product) {
        return jdbc.queryForObject("SELECT MAX(id) FROM reorder_proposals WHERE product_id = ?", Long.class, product);
    }

    private static List<List<Object>> lines(JsonNode order) {
        return java.util.stream.StreamSupport.stream(order.get("lines").spliterator(), false)
                .map(l -> List.<Object>of(l.get("product").get("id").asLong(), l.get("quantity").asString(),
                        l.get("unitPrice").asString(), l.get("discountPct").asString()))
                .toList();
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
