package com.imsvet.ims.catalog;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
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
 * ADR-048 / ADR-047 search behaviour on a small dataset (performance: ProductSearchPerformanceTest).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ProductSearchTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    private ApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        TestUser user = new TestUsers(jdbc, encoder).create("Pharmacist");
        client = ApiClient.create(context, "198.51.100.160");
        client.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void matchesAWordInsideNameSkuOrIngredientIgnoringCaseAndAccents() throws Exception {
        long amoxival = product("AMX-10", "Amoxival 500");
        long clamoxyl = product("CLX-1", "Clamoxyl LA");
        long vermi = product("VRM-1", "Vermifuge Vétérinaire");
        long ivo = product("IVO-1", "Ivomec");
        long ivermectin = ingredient("Ivermectine");
        ingredients(ivo, ivermectin);

        names("q=AMOX").andExpect(jsonPath("$.content[*].id", containsInAnyOrder((int) amoxival, (int) clamoxyl)));
        names("q=veterinaire").andExpect(jsonPath("$.content[*].id", contains((int) vermi)));
        names("q=ivermec").andExpect(jsonPath("$.content[*].id", contains((int) ivo)));
        names("q=clx-").andExpect(jsonPath("$.content[*].id", contains((int) clamoxyl)));
    }

    @Test
    void exactSkuFirstThenNamesStartingWithTheTermThenOthers() throws Exception {
        long contains = product("C-1", "Aaa amox inside");
        long prefix = product("P-1", "Amox prefix");
        long exactSku = product("AMOX", "Zzz exact sku");

        names("q=amox").andExpect(jsonPath("$.content[*].id",
                contains((int) exactSku, (int) prefix, (int) contains)));
    }

    @Test
    void filtersByCategoryManufacturerIngredientAndSpecies() throws Exception {
        long antibiotic = seeded("therapeutic_categories", "ANTIBIOTIC");
        long manufacturer = idOf(client.post("/api/manufacturers", "{\"name\":\"Filter Labs\"}"));
        long ingredient = ingredient("Oxytetracycline");
        long bovine = seeded("animal_species", "BOVINE");
        long categorised = product("F-1", "Categorised", "\"categoryId\":" + antibiotic);
        long made = product("F-2", "Made", "\"manufacturerId\":" + manufacturer);
        long withIngredient = product("F-3", "With ingredient");
        ingredients(withIngredient, ingredient);
        long targeted = product("F-4", "Targeted");
        long withPeriod = product("F-5", "With period");
        put(targeted, "target-species", "{\"speciesIds\":[" + bovine + "]}");
        put(withPeriod, "withdrawal-periods", "[{\"speciesId\":" + bovine + ",\"meatDays\":7}]");

        names("categoryId=" + antibiotic).andExpect(jsonPath("$.content[*].id", contains((int) categorised)));
        names("manufacturerId=" + manufacturer).andExpect(jsonPath("$.content[*].id", contains((int) made)));
        names("ingredientId=" + ingredient).andExpect(jsonPath("$.content[*].id", contains((int) withIngredient)));
        names("speciesId=" + bovine).andExpect(jsonPath("$.content[*].id",
                containsInAnyOrder((int) targeted, (int) withPeriod)));
    }

    @Test
    void archivedProductsOnlyOnRequest() throws Exception {
        long archived = product("A-1", "Archived search");
        client.post("/api/products/" + archived + "/archive", "").andExpect(status().isOk());

        names("q=archived search").andExpect(jsonPath("$.content").isEmpty());
        names("q=archived search&includeArchived=true").andExpect(jsonPath("$.content[*].id", contains((int) archived)));
    }

    @Test
    void wildcardsAreLiteral() throws Exception {
        long percent = product("W-1", "Solution 100% pure");
        product("W-2", "Solution 1000 diluted");

        byQ("100%").andExpect(jsonPath("$.content[*].id", contains((int) percent)));
        byQ("_").andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void pagesTheResults() throws Exception {
        for (int i = 0; i < 5; i++) {
            product("PG-" + i, "Paged " + i);
        }

        names("q=paged&size=2&page=1").andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(5))
                .andExpect(jsonPath("$.content[0].commercialName").value("Paged 2"));
    }

    private ResultActions names(String query) throws Exception {
        return client.perform(MockMvcRequestBuilders.get("/api/products?" + query + (query.contains("size=") ? "" : "&size=100")),
                true).andExpect(status().isOk());
    }

    /** {@code q} passed raw: a URI template would re-encode {@code %}. */
    private ResultActions byQ(String q) throws Exception {
        return client.perform(MockMvcRequestBuilders.get("/api/products").param("q", q).param("size", "100"), true)
                .andExpect(status().isOk());
    }

    private long product(String sku, String name) throws Exception {
        return product(sku, name, null);
    }

    private long product(String sku, String name, String extra) throws Exception {
        return idOf(client.post("/api/products", "{\"sku\":\"" + sku + "\",\"commercialName\":\"" + name
                + "\",\"baseUnit\":\"ml\",\"divisible\":true" + (extra == null ? "" : "," + extra) + "}"));
    }

    private long ingredient(String name) throws Exception {
        return idOf(client.post("/api/active-ingredients", "{\"name\":\"" + name + "\"}"));
    }

    private void ingredients(long product, long ingredient) throws Exception {
        put(product, "ingredients", "[{\"ingredientId\":" + ingredient + "}]");
    }

    private void put(long product, String path, String json) throws Exception {
        client.perform(MockMvcRequestBuilders.put("/api/products/" + product + "/" + path)
                .contentType(MediaType.APPLICATION_JSON).content(json), true).andExpect(status().isOk());
    }

    private long seeded(String table, String code) {
        // The table names are constants of this test, never input.
        return jdbc.queryForObject("SELECT id FROM " + table + " WHERE code = ?", Long.class, code);
    }

    private static long idOf(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
