package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Stock levels (BR-STOCK-011/013, ADR-080, ADR-089, ADR-090): on the product list (stock columns, in-stock filter,
 * stock-aware substitutes) and on {@code GET /api/inventory/stock} (status, below reorder, category, search). Sellable
 * = stored active, not expired at the business date, with stock. Filters run in the database through constant
 * conditions with bind parameters: a hostile search term finds nothing and changes nothing.
 * Business date 2026-10-20 (Africa/Algiers).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class StockLevelsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    private ApiClient seller;
    private long alpha;
    private long beta;
    private long gamma;
    private long delta;

    @BeforeEach
    void stock() throws Exception {
        clock.reset();
        TestLots lots = new TestLots(jdbc);
        alpha = lots.product("STK-ALPHA", "Alpha 500");
        beta = lots.product("STK-BETA", "Beta 250");
        gamma = lots.product("STK-GAMMA", "Gamma 100");
        delta = lots.product("STK-DELTA", "Delta 50");
        thresholds(alpha, "10", "20", "100");
        thresholds(beta, "10", "20", "60");
        thresholds(delta, "5", "5", null);
        lots.lot(alpha, "A1", TODAY.plusDays(100), "30");
        lots.lot(alpha, "A2", TODAY.minusDays(1), "50");
        lots.lot(alpha, "A3", TODAY.plusDays(20), "40", "blocked");
        lots.lot(beta, "B1", TODAY.plusDays(50), "8");
        lots.lot(beta, "B2", TODAY.plusDays(10), "0");
        lots.lot(delta, "D1", TODAY.plusDays(90), "9", "recalled");
        long ingredient = ingredient("STK Oxacillin");
        for (long product : new long[] { alpha, beta, gamma }) {
            jdbc.update("INSERT INTO product_active_ingredients (product_id, ingredient_id) VALUES (?, ?)", product,
                    ingredient);
        }
        TestUser user = new TestUsers(jdbc, encoder).create("Seller");
        seller = ApiClient.create(context, "198.51.100.220");
        seller.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void theProductListCarriesSellableAndTotalStockStatusAndNearestExpiry() throws Exception {
        get("/api/products?q=STK-&size=50").andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(4))
                .andExpect(jsonPath("$.content[0].commercialName").value("Alpha 500"))
                .andExpect(jsonPath("$.content[0].sellableQtyBase").value("30.000"))
                .andExpect(jsonPath("$.content[0].totalQtyBase").value("120.000"))
                .andExpect(jsonPath("$.content[0].stockStatus").value("in_stock"))
                .andExpect(jsonPath("$.content[0].nearestExpiry").value("2027-01-28"))
                .andExpect(jsonPath("$.content[1].commercialName").value("Beta 250"))
                .andExpect(jsonPath("$.content[1].stockStatus").value("low"))
                .andExpect(jsonPath("$.content[1].nearestExpiry").value("2026-12-09"))
                .andExpect(jsonPath("$.content[2].commercialName").value("Delta 50"))
                .andExpect(jsonPath("$.content[2].stockStatus").value("out"))
                .andExpect(jsonPath("$.content[2].totalQtyBase").value("9.000"))
                .andExpect(jsonPath("$.content[3].commercialName").value("Gamma 100"))
                .andExpect(jsonPath("$.content[3].sellableQtyBase").value("0.000"))
                .andExpect(jsonPath("$.content[3].nearestExpiry").doesNotExist());
    }

    @Test
    void inStockOnlyKeepsProductsWithSellableStock() throws Exception {
        get("/api/products?q=STK-&inStockOnly=true").andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].commercialName", containsInAnyOrder("Alpha 500", "Beta 250")));
    }

    @Test
    void substitutesAreTheProductsWithSellableStock() throws Exception {
        get("/api/products/" + gamma + "/substitutes").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].commercialName").value("Alpha 500"))
                .andExpect(jsonPath("$[0].sellableQtyBase").value("30.000"))
                .andExpect(jsonPath("$[1].commercialName").value("Beta 250"));
    }

    @Test
    void theStockListFiltersByStatusBelowReorderCategoryAndSearch() throws Exception {
        get("/api/inventory/stock?q=STK-&size=50").andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(4))
                .andExpect(jsonPath("$.content[0].product.sku").value("STK-ALPHA"))
                .andExpect(jsonPath("$.content[0].totalQty").value("120.000"))
                .andExpect(jsonPath("$.content[0].sellableQty").value("30.000"))
                .andExpect(jsonPath("$.content[0].minStock").value("10.000"))
                .andExpect(jsonPath("$.content[0].maxStock").value("100.000"))
                .andExpect(jsonPath("$.content[0].reorderPoint").value("20.000"))
                .andExpect(jsonPath("$.content[0].status").value("in_stock"))
                .andExpect(jsonPath("$.content[0].belowReorder").value(false))
                .andExpect(jsonPath("$.content[0].nearestSellableExpiry").value("2027-01-28"));
        get("/api/inventory/stock?q=STK-&status=low").andExpect(jsonPath("$.content[*].product.sku",
                containsInAnyOrder("STK-BETA")));
        get("/api/inventory/stock?q=STK-&status=out").andExpect(jsonPath("$.content[*].product.sku",
                containsInAnyOrder("STK-GAMMA", "STK-DELTA")));
        get("/api/inventory/stock?q=STK-&status=in_stock").andExpect(jsonPath("$.content[*].product.sku",
                containsInAnyOrder("STK-ALPHA")));
        get("/api/inventory/stock?q=STK-&belowReorder=true").andExpect(jsonPath("$.content[*].product.sku",
                containsInAnyOrder("STK-BETA", "STK-DELTA")));
        long category = jdbc.queryForObject("SELECT id FROM therapeutic_categories ORDER BY id LIMIT 1", Long.class);
        jdbc.update("UPDATE products SET category_id = ? WHERE id = ?", category, alpha);
        get("/api/inventory/stock?q=STK-&categoryId=" + category).andExpect(jsonPath("$.content[*].product.sku",
                containsInAnyOrder("STK-ALPHA")));
        get("/api/inventory/stock?status=sold").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status"));
    }

    /** ADR-090 condition 2: values are bound, never concatenated into the query text. */
    @ParameterizedTest
    @ValueSource(strings = { "STK-' OR '1'='1", "%", "_", "STK-%' --", "'; DROP TABLE lots; --",
            "STK-\\') OR 1=1 #", "x\" UNION SELECT id FROM users --" })
    void aHostileSearchTermFindsNothingAndChangesNothing(String term) throws Exception {
        int lotsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM lots", Integer.class);

        search("/api/products", term, "&inStockOnly=true").andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
        search("/api/inventory/stock", term, "&status=in_stock").andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
        search("/api/inventory/stock", term, "&belowReorder=true").andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lots", Integer.class)).isEqualTo(lotsBefore);
    }

    private ResultActions get(String uri) throws Exception {
        return seller.get(uri);
    }

    private ResultActions search(String path, String term, String more) throws Exception {
        return seller.perform(MockMvcRequestBuilders.get(path + "?size=50" + more).param("q", term), true);
    }

    private void thresholds(long product, String min, String reorder, String max) {
        jdbc.update("UPDATE products SET min_stock = ?, reorder_point = ?, max_stock = ? WHERE id = ?",
                new BigDecimal(min), new BigDecimal(reorder),
                max == null ? null : new BigDecimal(max), product);
    }

    private long ingredient(String name) {
        jdbc.update("INSERT INTO active_ingredients (name) VALUES (?)", name);
        return jdbc.queryForObject("SELECT id FROM active_ingredients WHERE name = ?", Long.class, name);
    }
}
