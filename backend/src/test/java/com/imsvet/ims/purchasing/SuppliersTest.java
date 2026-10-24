package com.imsvet.ims.purchasing;

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
 * Suppliers (FR-PURCH-001, ADR-100): laboratories and wholesalers with optional tax id and payment terms; the name
 * is unique ignoring case and accents; deactivated, never deleted (BR-PURCH-011). Manual price-history entries per
 * base unit (FR-PURCH-002, BR-PURCH-008).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SuppliersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    private ApiClient pharmacist;
    private TestUser user;

    @BeforeEach
    void login() throws Exception {
        clock.reset();
        user = new TestUsers(jdbc, encoder).create("Pharmacist");
        jdbc.update("UPDATE users SET full_name = 'Amina Benali' WHERE id = ?", user.id());
        pharmacist = ApiClient.create(context, "198.51.100.243");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void aSupplierIsCreatedReadAndListed() throws Exception {
        ResultActions created = create("{\"name\":\"Laboratoire Saïdal\",\"kind\":\"laboratory\",\"taxId\":\"000016001234567\","
                + "\"phone\":\"021 00 00 00\",\"email\":\"commandes@saidal.example\",\"address\":\"Alger\","
                + "\"paymentTermsDays\":30}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Laboratoire Saïdal"))
                .andExpect(jsonPath("$.kind").value("laboratory"))
                .andExpect(jsonPath("$.paymentTermsDays").value(30))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.version").value(0));
        long id = idOf(created);

        pharmacist.get("/api/suppliers/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.taxId").value("000016001234567"))
                .andExpect(jsonPath("$.email").value("commandes@saidal.example"));
        pharmacist.get("/api/suppliers?q=saidal").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(hasItem((int) id)));
        pharmacist.get("/api/suppliers?q=nothing-like-this").andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void theNameIsUniqueIgnoringCaseAndAccents() throws Exception {
        create("{\"name\":\"Grossiste Médical Est\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("wholesaler"))
                .andExpect(jsonPath("$.taxId").isEmpty())
                .andExpect(jsonPath("$.paymentTermsDays").value(0));

        create("{\"name\":\"GROSSISTE medical est\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.context.field").value("name"));
    }

    @Test
    void invalidFieldsAreNamed() throws Exception {
        assertFieldError(create("{\"name\":\"  \"}"), "name", "REQUIRED");
        assertFieldError(create("{\"name\":\"X\",\"kind\":\"retailer\"}"), "kind", "UNKNOWN");
        assertFieldError(create("{\"name\":\"X\",\"paymentTermsDays\":-1}"), "paymentTermsDays", "RANGE");
        assertFieldError(create("{\"name\":\"X\",\"email\":\"not-an-address\"}"), "email", "EMAIL");
    }

    @Test
    void anUpdateNeedsTheCurrentVersion() throws Exception {
        long id = idOf(create("{\"name\":\"Vet Distribution\"}"));

        update(id, "{\"name\":\"Vet Distribution Ouest\",\"kind\":\"wholesaler\",\"paymentTermsDays\":45,\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Vet Distribution Ouest"))
                .andExpect(jsonPath("$.version").value(1));
        update(id, "{\"name\":\"Stale\",\"kind\":\"wholesaler\",\"version\":0}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
        idOf(create("{\"name\":\"Other Supplier\"}"));
        update(id, "{\"name\":\"other supplier\",\"kind\":\"wholesaler\",\"version\":1}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void aSupplierIsDeactivatedAndReactivated() throws Exception {
        long id = idOf(create("{\"name\":\"Seasonal Supplier\"}"));

        pharmacist.post("/api/suppliers/" + id + "/deactivate", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        pharmacist.get("/api/suppliers?active=true").andExpect(jsonPath("$.content[*].id").value(not(hasItem((int) id))));
        pharmacist.get("/api/suppliers?active=false").andExpect(jsonPath("$.content[*].id").value(hasItem((int) id)));
        pharmacist.post("/api/suppliers/" + id + "/activate", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        pharmacist.post("/api/suppliers/999999/deactivate", "").andExpect(status().isNotFound());
    }

    @Test
    void manualPricesArePerBaseUnitAndNewestFirst() throws Exception {
        long supplier = idOf(create("{\"name\":\"Price Supplier\"}"));
        long product = new TestLots(jdbc).product("SUP-PRICE", "Priced tablets");

        addPrice(supplier, "{\"productId\":" + product + ",\"unitPrice\":\"0.1250\",\"discountPct\":\"5\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.unitPrice").value("0.1250"))
                .andExpect(jsonPath("$.discountPct").value("5.00"))
                .andExpect(jsonPath("$.validFrom").value("2026-10-20"))
                .andExpect(jsonPath("$.product.sku").value("SUP-PRICE"))
                .andExpect(jsonPath("$.createdBy.fullName").value("Amina Benali"));
        addPrice(supplier, "{\"productId\":" + product + ",\"unitPrice\":\"0.13\",\"discountPct\":\"0\","
                + "\"validFrom\":\"2026-11-01\"}").andExpect(status().isCreated());

        pharmacist.get("/api/suppliers/" + supplier + "/prices?productId=" + product).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].unitPrice").value("0.1300"))
                .andExpect(jsonPath("$.content[0].validFrom").value("2026-11-01"))
                .andExpect(jsonPath("$.content[1].unitPrice").value("0.1250"));
        assertFieldError(addPrice(supplier, "{\"productId\":" + product + ",\"unitPrice\":\"-1\"}"), "unitPrice",
                "NOT_NEGATIVE");
        assertFieldError(addPrice(supplier, "{\"productId\":" + product + ",\"unitPrice\":\"0.12345\"}"), "unitPrice",
                "SCALE");
        assertFieldError(addPrice(supplier, "{\"productId\":" + product + ",\"unitPrice\":\"1\",\"discountPct\":\"101\"}"),
                "discountPct", "RANGE");
        assertFieldError(addPrice(supplier, "{\"productId\":999999,\"unitPrice\":\"1\"}"), "productId", "UNKNOWN");
    }

    @Test
    void anArchivedProductGetsNoNewPrice() throws Exception {
        long supplier = idOf(create("{\"name\":\"Archive Supplier\"}"));
        long product = new TestLots(jdbc).product("SUP-ARCH", "Archived");
        jdbc.update("UPDATE products SET is_archived = TRUE WHERE id = ?", product);

        addPrice(supplier, "{\"productId\":" + product + ",\"unitPrice\":\"1\"}").andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));
    }

    private ResultActions create(String body) throws Exception {
        return pharmacist.post("/api/suppliers", body);
    }

    private ResultActions update(long id, String body) throws Exception {
        return pharmacist.perform(MockMvcRequestBuilders.put("/api/suppliers/" + id)
                .contentType(MediaType.APPLICATION_JSON).content(body), true);
    }

    private ResultActions addPrice(long supplier, String body) throws Exception {
        return pharmacist.post("/api/suppliers/" + supplier + "/prices", body);
    }

    private static long idOf(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private static void assertFieldError(ResultActions actions, String field, String code) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code));
    }
}
