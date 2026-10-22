package com.imsvet.ims.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import jakarta.persistence.EntityManager;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Packaging units over the API (FR-PROD-006/007): BR-UNIT-001 (factor > 0, ≤ 4 decimals, whole for a
 * non-divisible product, name unique per product), ADR-063 (optional unit price, else derived and returned as
 * {@code effectiveSalePrice}), BR-PROD-006 for unit prices (needs {@code price.edit}; changes audited, the price
 * given at creation not), BR-UNIT-004 for an unused unit, optimistic locking (ADR-027).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ProductUnitsTest {

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
    private long tablets;

    @BeforeEach
    void logIn() throws Exception {
        clock.reset();
        TestUser user = new TestUsers(jdbc, encoder).create("Pharmacist");
        pharmacist = ApiClient.create(context, "198.51.100.140");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
        tablets = product("UNIT-TAB", false, "0.0125");
    }

    @Test
    void addsAndListsUnitsWithStoredOrDerivedPrice() throws Exception {
        add(tablets, "{\"name\":\"Box\",\"factorToBase\":\"30\",\"sellable\":true}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Box"))
                .andExpect(jsonPath("$.factorToBase").value("30.0000"))
                .andExpect(jsonPath("$.salePrice").doesNotExist())
                .andExpect(jsonPath("$.effectiveSalePrice").value("0.3750"))
                .andExpect(jsonPath("$.sellable").value(true))
                .andExpect(jsonPath("$.version").value(0));
        add(tablets, "{\"name\":\"Blister\",\"factorToBase\":\"10\",\"salePrice\":\"0.15\",\"sellable\":false}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.salePrice").value("0.15"))
                .andExpect(jsonPath("$.effectiveSalePrice").value("0.1500"));

        pharmacist.get("/api/products/" + tablets + "/units").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Blister"))
                .andExpect(jsonPath("$[1].name").value("Box"));
        assertThat(priceAudits()).isZero();
    }

    @Test
    void aDivisibleProductAcceptsDecimalFactors() throws Exception {
        long solution = product("UNIT-SOL", true, "0.5");

        add(solution, "{\"name\":\"Dose\",\"factorToBase\":\"2.5\",\"sellable\":true}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveSalePrice").value("1.2500"));
    }

    @Test
    void invalidUnitsAreFieldErrors() throws Exception {
        assertFieldError(add(tablets, "{\"name\":\"A\",\"factorToBase\":\"0\",\"sellable\":true}"), "factorToBase",
                "POSITIVE");
        assertFieldError(add(tablets, "{\"name\":\"B\",\"factorToBase\":\"1.00001\",\"sellable\":true}"),
                "factorToBase", "SCALE");
        assertFieldError(add(tablets, "{\"name\":\"C\",\"factorToBase\":\"2.5\",\"sellable\":true}"), "factorToBase",
                "WHOLE_NUMBER");
        assertFieldError(add(tablets, "{\"name\":\"D\",\"factorToBase\":\"2\",\"salePrice\":\"-1\",\"sellable\":true}"),
                "salePrice", "NOT_NEGATIVE");
        assertFieldError(add(tablets, "{\"name\":\"E\",\"factorToBase\":\"2\",\"salePrice\":\"1.001\",\"sellable\":true}"),
                "salePrice", "SCALE");
        assertFieldError(add(tablets, "{\"name\":\" \",\"factorToBase\":\"2\",\"sellable\":true}"), "name", "REQUIRED");
        assertFieldError(add(tablets, "{\"name\":\"" + "x".repeat(41) + "\",\"factorToBase\":\"2\",\"sellable\":true}"),
                "name", "SIZE");
        assertFieldError(add(tablets, "{\"name\":\"F\",\"sellable\":true}"), "factorToBase", "REQUIRED");
    }

    @Test
    void aUnitNameIsUniqueWithinTheProductIgnoringCase() throws Exception {
        long other = product("UNIT-OTHER", false, "1");
        add(tablets, "{\"name\":\"Box\",\"factorToBase\":\"30\",\"sellable\":true}").andExpect(status().isCreated());

        add(tablets, "{\"name\":\" box \",\"factorToBase\":\"20\",\"sellable\":true}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.context.field").value("name"));
        add(other, "{\"name\":\"Box\",\"factorToBase\":\"20\",\"sellable\":true}").andExpect(status().isCreated());
    }

    @Test
    void anUnusedUnitCanBeEditedIncludingItsFactor() throws Exception {
        long unit = idOf(add(tablets, "{\"name\":\"Box\",\"factorToBase\":\"30\",\"sellable\":true}"));

        update(tablets, unit, "{\"name\":\"Box of 20\",\"factorToBase\":\"20\",\"sellable\":false,\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Box of 20"))
                .andExpect(jsonPath("$.factorToBase").value("20.0000"))
                .andExpect(jsonPath("$.effectiveSalePrice").value("0.2500"))
                .andExpect(jsonPath("$.sellable").value(false))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void renamingOntoAnotherUnitIsADuplicate() throws Exception {
        add(tablets, "{\"name\":\"Box\",\"factorToBase\":\"30\",\"sellable\":true}");
        long blister = idOf(add(tablets, "{\"name\":\"Blister\",\"factorToBase\":\"10\",\"sellable\":true}"));

        update(tablets, blister, "{\"name\":\"BOX\",\"factorToBase\":\"10\",\"sellable\":true,\"version\":0}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void aUnitPriceChangeIsAuditedWithOldAndNewValuesButTheInitialPriceIsNot() throws Exception {
        long unit = idOf(add(tablets,
                "{\"name\":\"Box\",\"factorToBase\":\"30\",\"salePrice\":\"0.35\",\"sellable\":true}"));
        assertThat(priceAudits()).isZero();

        update(tablets, unit, "{\"name\":\"Box\",\"factorToBase\":\"30\",\"salePrice\":\"0.40\",\"sellable\":true,"
                + "\"version\":0}").andExpect(status().isOk()).andExpect(jsonPath("$.salePrice").value("0.40"));
        update(tablets, unit, "{\"name\":\"Box\",\"factorToBase\":\"30\",\"sellable\":true,\"version\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salePrice").doesNotExist())
                .andExpect(jsonPath("$.effectiveSalePrice").value("0.3750"));
        update(tablets, unit, "{\"name\":\"Box\",\"factorToBase\":\"30\",\"sellable\":true,\"version\":2}")
                .andExpect(status().isOk());

        var audits = jdbc.queryForList("""
                SELECT old_values->>'$.salePrice' AS old_price, new_values->>'$.salePrice' AS new_price,
                       JSON_TYPE(new_values->'$.salePrice') AS new_type
                FROM audit_log WHERE action = 'PRICE_CHANGE' AND entity = 'product_units' AND entity_id = ?
                ORDER BY id""", unit);
        assertThat(audits).hasSize(2);
        assertThat(audits.get(0)).containsEntry("old_price", "0.35").containsEntry("new_price", "0.40");
        assertThat(audits.get(1)).containsEntry("old_price", "0.40").containsEntry("new_type", "NULL");
    }

    @Test
    void withoutPriceEditAUnitPriceCanBeNeitherSetChangedNorCleared() throws Exception {
        long priced = idOf(add(tablets,
                "{\"name\":\"Box\",\"factorToBase\":\"30\",\"salePrice\":\"0.35\",\"sellable\":true}"));
        jdbc.update("""
                DELETE rp FROM role_permissions rp JOIN roles r ON r.id = rp.role_id
                JOIN permissions p ON p.id = rp.permission_id WHERE r.name = 'Pharmacist' AND p.code = 'price.edit'""");
        entityManager.flush();
        entityManager.clear();

        add(tablets, "{\"name\":\"Pack\",\"factorToBase\":\"60\",\"salePrice\":\"0.6\",\"sellable\":true}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        add(tablets, "{\"name\":\"Pack\",\"factorToBase\":\"60\",\"sellable\":true}").andExpect(status().isCreated());
        update(tablets, priced, "{\"name\":\"Box\",\"factorToBase\":\"30\",\"salePrice\":\"0.36\",\"sellable\":true,"
                + "\"version\":0}").andExpect(status().isForbidden());
        update(tablets, priced, "{\"name\":\"Box\",\"factorToBase\":\"30\",\"sellable\":true,\"version\":0}")
                .andExpect(status().isForbidden());
        update(tablets, priced, "{\"name\":\"Big box\",\"factorToBase\":\"30\",\"salePrice\":\"0.350\","
                + "\"sellable\":true,\"version\":0}").andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT sale_price FROM product_units WHERE id = ?", String.class, priced))
                .isEqualTo("0.35");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_units WHERE name = 'Pack' AND sale_price IS NULL",
                Integer.class)).isEqualTo(1);
        assertThat(priceAudits()).isZero();
    }

    @Test
    void aStaleVersionIsAConcurrentModification() throws Exception {
        long unit = idOf(add(tablets, "{\"name\":\"Box\",\"factorToBase\":\"30\",\"sellable\":true}"));
        update(tablets, unit, "{\"name\":\"Box\",\"factorToBase\":\"30\",\"sellable\":false,\"version\":0}")
                .andExpect(status().isOk());

        update(tablets, unit, "{\"name\":\"Box\",\"factorToBase\":\"25\",\"sellable\":true,\"version\":0}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void aUnitIsOnlyReachedThroughItsOwnProduct() throws Exception {
        long other = product("UNIT-OTHER-2", false, "1");
        long unit = idOf(add(tablets, "{\"name\":\"Box\",\"factorToBase\":\"30\",\"sellable\":true}"));

        update(other, unit, "{\"name\":\"Box\",\"factorToBase\":\"30\",\"sellable\":true,\"version\":0}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        pharmacist.get("/api/products/999999/units").andExpect(status().isNotFound());
        add(999999, "{\"name\":\"Box\",\"factorToBase\":\"30\",\"sellable\":true}").andExpect(status().isNotFound());
    }

    private long product(String sku, boolean divisible, String salePrice) throws Exception {
        return idOf(pharmacist.post("/api/products", "{\"sku\":\"" + sku + "\",\"commercialName\":\"" + sku + "\","
                + "\"baseUnit\":\"" + (divisible ? "ml" : "tablet") + "\",\"divisible\":" + divisible
                + ",\"salePrice\":\"" + salePrice + "\"}").andExpect(status().isCreated()));
    }

    private ResultActions add(long productId, String body) throws Exception {
        return pharmacist.post("/api/products/" + productId + "/units", body);
    }

    private ResultActions update(long productId, long unitId, String body) throws Exception {
        return pharmacist.perform(MockMvcRequestBuilders.put("/api/products/" + productId + "/units/" + unitId)
                .contentType(MediaType.APPLICATION_JSON).content(body), true);
    }

    private static void assertFieldError(ResultActions actions, String field, String code) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code));
    }

    private int priceAudits() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'PRICE_CHANGE' AND entity = 'product_units'",
                Integer.class);
    }

    private static long idOf(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
