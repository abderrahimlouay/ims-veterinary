package com.imsvet.ims.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.MigrationAccount;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * NFR-002 / ADR-048 (testing.md §8.1): on the reference dataset of 50,000 products, 2,000 active ingredients
 * (1–3 per product), seeded categories, dosage forms and species, target species and withdrawal periods,
 * every representative search through the API answers in under 1 s. From Phase 4 (ADR-090) the dataset also has
 * lots for two products in three (varied expiries, some expired, some blocked) and thresholds on half of the
 * products, and the in-stock searches of the product list and of the stock list are measured too.
 * The dataset is committed in the database for committed data and the statistics are refreshed ({@code ANALYZE
 * TABLE}), as production statistics are after a load: planned against an uncommitted bulk load, MySQL still saw an
 * empty {@code lots} table and chose full scans for the correlated stock conditions (measured 2026-10-23).
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class ProductSearchPerformanceTest {

    private static final int PRODUCTS = 50_000;
    private static final long LIMIT_MILLIS = 1_000;
    /** 0..99,999 from five cross-joined digits; numbers only, no input. */
    private static final String NUMBERS = """
            WITH d (n) AS (SELECT 0 UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                           UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9),
                 numbers (n) AS (SELECT a.n + 10 * b.n + 100 * c.n + 1000 * e.n + 10000 * f.n
                                 FROM d a, d b, d c, d e, d f)""";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    @Autowired
    private MigrationAccount migration;

    @Test
    void everyRepresentativeSearchAnswersInUnderOneSecond() throws Exception {
        clock.reset();
        generateDataset();
        TestUser user = new TestUsers(jdbc, encoder).create("Seller");
        ApiClient client = ApiClient.create(context, "198.51.100.170");
        client.login(user.username(), user.password()).andExpect(status().isOk());
        long bovine = jdbc.queryForObject("SELECT id FROM animal_species WHERE code = 'BOVINE'", Long.class);

        Map<String, String> searches = new LinkedHashMap<>();
        searches.put("word inside names", "q=cillin");
        searches.put("exact SKU", "q=PERF-12345");
        searches.put("active ingredient name", "q=Ingredient 1234");
        searches.put("species filter", "speciesId=" + bovine);
        searches.put("archived included", "q=vet&includeArchived=true");
        searches.put("no match", "q=zzzz-no-such-product");
        searches.put("in stock only", "inStockOnly=true");
        searches.put("word inside names, in stock", "q=cillin&inStockOnly=true");
        searches.put("stock list, low", "/api/inventory/stock?status=low");
        searches.put("stock list, below reorder", "/api/inventory/stock?belowReorder=true");
        searches.put("stock list, word, in stock", "/api/inventory/stock?q=vet&status=in_stock");
        search(client, "q=warm-up");
        search(client, "/api/inventory/stock?q=warm-up");

        Map<String, Long> millis = new LinkedHashMap<>();
        Map<String, Long> totals = new LinkedHashMap<>();
        for (Map.Entry<String, String> s : searches.entrySet()) {
            long start = System.nanoTime();
            String body = search(client, s.getValue());
            millis.put(s.getKey(), (System.nanoTime() - start) / 1_000_000);
            totals.put(s.getKey(), JsonMapper.builder().build().readTree(body).at("/page/totalElements").asLong());
        }
        System.out.printf("NFR-002 search times on %d products (ms): %s; matches: %s%n", PRODUCTS, millis, totals);

        assertThat(totals.get("word inside names")).isPositive();
        assertThat(totals.get("exact SKU")).isPositive();
        assertThat(totals.get("active ingredient name")).isPositive();
        assertThat(totals.get("species filter")).isPositive();
        assertThat(totals.get("no match")).isZero();
        assertThat(totals.get("in stock only")).isPositive();
        assertThat(totals.get("stock list, low")).isPositive();
        assertThat(totals.get("stock list, below reorder")).isPositive();
        assertThat(millis).allSatisfy((name, ms) -> assertThat(ms).as(name).isLessThan(LIMIT_MILLIS));
    }

    /** A query string for the product list, or a full path for another list. */
    private static String search(ApiClient client, String query) throws Exception {
        String uri = query.startsWith("/") ? query + (query.contains("?") ? "&" : "?") + "size=20"
                : "/api/products?size=20&" + query;
        return client.perform(MockMvcRequestBuilders.get(uri), true)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void generateDataset() {
        jdbc.update("INSERT INTO active_ingredients (name) " + NUMBERS
                + " SELECT CONCAT('Ingredient ', n) FROM numbers WHERE n < 2000");
        jdbc.update("INSERT INTO products (sku, commercial_name, category_id, dosage_form_id, base_unit, is_divisible,"
                + " vat_rate, sale_price, is_archived, created_at, updated_at) " + NUMBERS + """
                 SELECT CONCAT('PERF-', n),
                        CONCAT(ELT(1 + n % 12, 'Amoxi', 'Oxytetra', 'Ivo', 'Doxy', 'Enro', 'Flor', 'Tylo', 'Peni',
                                   'Vermi', 'Calci', 'Vita', 'Meloxi'),
                               ELT(1 + (n DIV 12) % 8, 'cillin', 'vet', 'mec', 'cycline', 'floxacin', 'fenicol', 'sol',
                                   'jet'), ' ', n),
                        (SELECT id FROM therapeutic_categories ORDER BY id LIMIT 1),
                        (SELECT id FROM dosage_forms ORDER BY id LIMIT 1),
                        IF(n % 2 = 0, 'ml', 'tablet'), n % 2 = 0, 9.00, (n % 997) / 100, n % 50 = 0,
                        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                 FROM numbers WHERE n <""" + " " + PRODUCTS);
        String perfProducts = "SELECT p.id, p.id % 2000 AS k FROM products p WHERE p.sku LIKE 'PERF-%'";
        jdbc.update("INSERT IGNORE INTO product_active_ingredients (product_id, ingredient_id) "
                + "SELECT pp.id, i.id FROM (" + perfProducts + ") pp JOIN active_ingredients i "
                + "ON i.name = CONCAT('Ingredient ', pp.k)");
        jdbc.update("INSERT IGNORE INTO product_active_ingredients (product_id, ingredient_id) "
                + "SELECT pp.id, i.id FROM (" + perfProducts + ") pp JOIN active_ingredients i "
                + "ON i.name = CONCAT('Ingredient ', (pp.k * 7) % 2000) WHERE pp.id % 3 <> 0");
        jdbc.update("INSERT IGNORE INTO product_active_ingredients (product_id, ingredient_id) "
                + "SELECT pp.id, i.id FROM (" + perfProducts + ") pp JOIN active_ingredients i "
                + "ON i.name = CONCAT('Ingredient ', (pp.k * 13) % 2000) WHERE pp.id % 5 = 0");
        jdbc.update("INSERT INTO product_target_species (product_id, species_id) "
                + "SELECT p.id, s.id FROM products p JOIN animal_species s ON s.code IS NOT NULL AND p.id % 7 = s.id % 7 "
                + "WHERE p.sku LIKE 'PERF-%'");
        jdbc.update("INSERT INTO product_withdrawal_periods (product_id, species_id, meat_days, milk_days) "
                + "SELECT p.id, s.id, 28, NULL FROM products p JOIN animal_species s ON s.code = 'BOVINE' "
                + "WHERE p.sku LIKE 'PERF-%' AND p.id % 4 = 0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE sku LIKE 'PERF-%'", Integer.class))
                .isEqualTo(PRODUCTS);
        jdbc.update("UPDATE products SET min_stock = 20, reorder_point = 30, max_stock = 200 "
                + "WHERE sku LIKE 'PERF-%' AND id % 2 = 0");
        jdbc.update("""
                INSERT INTO lots (product_id, lot_number, expiry_date, unit_cost, quantity_on_hand, status, received_at)
                SELECT id, CONCAT('PL-', id), DATE_ADD('2026-10-20', INTERVAL (id % 400) - 30 DAY), 0.5, (id % 7) * 10,
                       IF(id % 23 = 0, 'blocked', 'active'), UTC_TIMESTAMP(6)
                FROM products WHERE sku LIKE 'PERF-%' AND id % 3 <> 0""");
        jdbc.update("""
                INSERT INTO lots (product_id, lot_number, expiry_date, unit_cost, quantity_on_hand, status, received_at)
                SELECT id, CONCAT('PM-', id), DATE_ADD('2026-10-20', INTERVAL (id % 200) + 10 DAY), 0.5, (id % 5) * 3,
                       'active', UTC_TIMESTAMP(6)
                FROM products WHERE sku LIKE 'PERF-%' AND id % 4 = 1""");
        for (String table : new String[] { "products", "lots", "product_active_ingredients", "active_ingredients",
                "product_target_species", "product_withdrawal_periods" }) {
            migration.jdbc().queryForList("ANALYZE TABLE " + table);
        }
    }
}
