package com.imsvet.ims.catalog;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * API test (A) of the product core (FR-PROD-001/002/009, api.md §4.3): BR-PROD-001, 003, 004 (readability
 * of archived products), 005; ADR-050 whole thresholds; ADR-027 version; VAT default from the settings.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ProductEndpointTest {

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

    @BeforeEach
    void logIn() throws Exception {
        clock.reset();
        TestUser user = new TestUsers(jdbc, encoder).create("Pharmacist");
        pharmacist = ApiClient.create(context, "198.51.100.120");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void createsAProductWithReferencesDefaultsAndNoPrices() throws Exception {
        create(product("AMX-10", "Amoxival 500", "tablet", false))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/products/")))
                .andExpect(jsonPath("$.sku").value("AMX-10"))
                .andExpect(jsonPath("$.category.code").value("ANTIBIOTIC"))
                .andExpect(jsonPath("$.dosageForm.code").value("TABLET"))
                .andExpect(jsonPath("$.manufacturer").doesNotExist())
                .andExpect(jsonPath("$.divisible").value(false))
                .andExpect(jsonPath("$.vatRate").value("9.00"))
                .andExpect(jsonPath("$.salePrice").value("0.0000"))
                .andExpect(jsonPath("$.purchasePrice").value("0.0000"))
                .andExpect(jsonPath("$.minStock").value("10.000"))
                .andExpect(jsonPath("$.archived").value(false))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void vatDefaultsToTheSettingAndCanBeGiven() throws Exception {
        jdbc.update("UPDATE app_settings SET setting_value = '19.00' WHERE setting_key = 'default_vat_rate'");
        create(product("VAT-1", "Vat default", "ml", true)).andExpect(jsonPath("$.vatRate").value("19.00"));

        create(product("VAT-2", "Vat given", "ml", true).replace("\"divisible\":true", "\"divisible\":true,\"vatRate\":\"0\""))
                .andExpect(jsonPath("$.vatRate").value("0.00"));
    }

    @Test
    void skuIsUniqueIgnoringCase() throws Exception {
        create(product("AMX-10", "First", "tablet", false)).andExpect(status().isCreated());

        create(product("amx-10", "Second", "tablet", false)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_SKU_DUPLICATE"))
                .andExpect(jsonPath("$.context.field").value("sku"));
    }

    @Test
    void invalidProductsAreFieldErrors() throws Exception {
        create(product("B-1", "No unit", " ", true)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("baseUnit"));
        create(product("B-2", "Negative", "ml", true).replace("\"minStock\":\"10\"", "\"minStock\":\"-1\""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("minStock"));
        create(product("B-3", "Max below min", "ml", true).replace("\"maxStock\":null", "\"maxStock\":\"5\""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("maxStock"));
        create(product("B-4", "Bad VAT", "ml", true).replace("\"divisible\":true", "\"divisible\":true,\"vatRate\":\"100.5\""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("vatRate"));
        create(product("B-5", "Unknown category", "ml", true).replace("\"categoryId\":" + id("therapeutic_categories", "ANTIBIOTIC"), "\"categoryId\":999999"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("categoryId"));
    }

    @Test
    void wholeProductsTakeWholeThresholdsDivisibleOnesThreeDecimals() throws Exception {
        create(product("W-1", "Tablets", "tablet", false).replace("\"minStock\":\"10\"", "\"minStock\":\"2.5\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("minStock"))
                .andExpect(jsonPath("$.errors[0].code").value("WHOLE_NUMBER"));
        create(product("W-2", "Solution", "ml", true).replace("\"minStock\":\"10\"", "\"minStock\":\"2.5\""))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.minStock").value("2.500"));
        create(product("W-3", "Too precise", "ml", true).replace("\"minStock\":\"10\"", "\"minStock\":\"2.5001\""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateChecksTheVersion() throws Exception {
        long id = idOf(create(product("U-1", "Before", "ml", true)));
        String changed = product("U-1", "After", "ml", true).replace("{", "{\"version\":0,\"vatRate\":\"9\",");

        put(id, changed).andExpect(status().isOk())
                .andExpect(jsonPath("$.commercialName").value("After"))
                .andExpect(jsonPath("$.version").value(1));
        put(id, changed).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void archivedProductsLeaveTheListButStayReadableAndEditable() throws Exception {
        long id = idOf(create(product("A-1", "Archivable", "ml", true)));

        pharmacist.post("/api/products/" + id + "/archive", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));
        pharmacist.get("/api/products?size=100").andExpect(jsonPath("$.content[?(@.id == " + id + ")]").isEmpty());
        pharmacist.get("/api/products?includeArchived=true&size=100")
                .andExpect(jsonPath("$.content[?(@.id == " + id + ")].archived").value(true));
        pharmacist.get("/api/products/" + id).andExpect(status().isOk());
        put(id, product("A-1", "Still editable", "ml", true).replace("{", "{\"version\":1,\"vatRate\":\"9\","))
                .andExpect(status().isOk());

        pharmacist.post("/api/products/" + id + "/restore", "").andExpect(jsonPath("$.archived").value(false));
    }

    /** ADR-053, deferred from the reference-lists slice: a referenced entry cannot be deleted. */
    @Test
    void aReferenceUsedByAProductCannotBeDeleted() throws Exception {
        long manufacturer = idOf(pharmacist.post("/api/manufacturers", "{\"name\":\"In Use Labs\"}"));
        create(product("R-1", "Uses manufacturer", "ml", true).replace("\"manufacturerId\":null",
                "\"manufacturerId\":" + manufacturer)).andExpect(status().isCreated());
        startNewRequestScope();

        pharmacist.perform(MockMvcRequestBuilders.delete("/api/manufacturers/" + manufacturer), true)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"));
    }

    @Test
    void sellersCanReadButNotCreate() throws Exception {
        long id = idOf(create(product("S-1", "Readable", "ml", true)));
        TestUser seller = new TestUsers(jdbc, encoder).create("Seller");
        ApiClient sellerClient = ApiClient.create(context, "198.51.100.121");
        sellerClient.login(seller.username(), seller.password()).andExpect(status().isOk());

        sellerClient.get("/api/products/" + id).andExpect(status().isOk());
        sellerClient.post("/api/products", product("S-2", "Refused", "ml", true)).andExpect(status().isForbidden());
    }

    @Test
    void updateRequiresTheVatRate() throws Exception {
        long id = idOf(create(product("U-2", "No VAT", "ml", true)));

        put(id, product("U-2", "No VAT", "ml", true).replace("{", "{\"version\":0,")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("vatRate"));
    }

    /**
     * In production each request has its own persistence context; inside this test's transaction they share
     * one, which must forget the product before the delete so that the database foreign key decides.
     */
    private void startNewRequestScope() {
        entityManager.flush();
        entityManager.clear();
    }

    private String product(String sku, String name, String baseUnit, boolean divisible) {
        return "{\"sku\":\"" + sku + "\",\"commercialName\":\"" + name + "\",\"categoryId\":"
                + id("therapeutic_categories", "ANTIBIOTIC") + ",\"dosageFormId\":" + id("dosage_forms", "TABLET")
                + ",\"manufacturerId\":null,\"baseUnit\":\"" + baseUnit + "\",\"divisible\":" + divisible
                + ",\"minStock\":\"10\",\"maxStock\":null,\"reorderPoint\":\"0\"}";
    }

    private long id(String table, String code) {
        // The table names are constants of this test, never input.
        return jdbc.queryForObject("SELECT id FROM " + table + " WHERE code = ?", Long.class, code);
    }

    private ResultActions create(String json) throws Exception {
        return pharmacist.post("/api/products", json);
    }

    private ResultActions put(long id, String json) throws Exception {
        return pharmacist.perform(MockMvcRequestBuilders.put("/api/products/" + id)
                .contentType(MediaType.APPLICATION_JSON).content(json), true);
    }

    private static long idOf(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
