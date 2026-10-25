package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import java.util.Map;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Customer categories (FR-SALE-005, BR-CUST-007, ADR-112): name unique ignoring case and accents, discount 0–100 %
 * with 2 decimals, renamed but never deleted; a discount change audited PRICE_CHANGE with old and new value (the
 * initial discount and a rename are not price changes); managed with customer.price.manage, read with customer.read.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CustomerCategoriesTest {

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

    @BeforeEach
    void login() throws Exception {
        clock.reset();
        TestUser user = new TestUsers(jdbc, encoder).create("Pharmacist");
        pharmacist = ApiClient.create(context, "198.51.100.249");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void categoriesAreCreatedAndListedByName() throws Exception {
        create("{\"name\":\"  Éleveurs laitiers \",\"discountPct\":\"5\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Éleveurs laitiers"))
                .andExpect(jsonPath("$.discountPct").value("5.00"))
                .andExpect(jsonPath("$.version").value(0));
        create("{\"name\":\"Aviculteurs\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.discountPct").value("0.00"));

        pharmacist.get("/api/customer-categories").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Aviculteurs"))
                .andExpect(jsonPath("$[1].name").value("Éleveurs laitiers"));
    }

    @Test
    void aNameIsUniqueIgnoringCaseAndAccents() throws Exception {
        long first = id(create("{\"name\":\"Éleveurs\",\"discountPct\":\"3\"}"));
        long second = id(create("{\"name\":\"Vétérinaires\",\"discountPct\":\"8\"}"));

        create("{\"name\":\"eleveurs\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.context.field").value("name"));
        update(second, "{\"name\":\"ÉLEVEURS\",\"discountPct\":\"8\",\"version\":0}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
        update(first, "{\"name\":\"eleveurs\",\"discountPct\":\"3\",\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("eleveurs"));
    }

    @Test
    void nameAndDiscountAreValidated() throws Exception {
        assertFieldError(create("{\"name\":\" \"}"), "name", "REQUIRED");
        assertFieldError(create("{\"name\":\"" + "x".repeat(81) + "\"}"), "name", "SIZE");
        assertFieldError(create("{\"name\":\"A\",\"discountPct\":\"-1\"}"), "discountPct", "RANGE");
        assertFieldError(create("{\"name\":\"A\",\"discountPct\":\"100.01\"}"), "discountPct", "RANGE");
        assertFieldError(create("{\"name\":\"A\",\"discountPct\":\"2.555\"}"), "discountPct", "SCALE");
        create("{\"name\":\"Full\",\"discountPct\":\"100\"}").andExpect(status().isCreated());
    }

    @Test
    void aDiscountChangeIsAuditedButNotARenameNorTheInitialDiscount() throws Exception {
        long id = id(create("{\"name\":\"Grossistes\",\"discountPct\":\"5\"}"));
        update(id, "{\"name\":\"Revendeurs\",\"discountPct\":\"5.00\",\"version\":0}").andExpect(status().isOk());
        update(id, "{\"name\":\"Revendeurs\",\"discountPct\":\"7.5\",\"version\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.discountPct").value("7.50"));
        update(id, "{\"name\":\"Revendeurs\",\"discountPct\":\"8\",\"version\":1}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

        assertThat(jdbc.queryForList("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(old_values, '$.discountPct')) AS old_pct,
                       JSON_UNQUOTE(JSON_EXTRACT(new_values, '$.discountPct')) AS new_pct
                FROM audit_log WHERE action = 'PRICE_CHANGE' AND entity = 'customer_categories' AND entity_id = ?""", id))
                .containsExactly(Map.of("old_pct", "5.00", "new_pct", "7.50"));
    }

    @Test
    void sellersReadButCannotManageCategoriesNorTheirDiscount() throws Exception {
        long id = id(create("{\"name\":\"Tous\"}"));
        TestUser seller = new TestUsers(jdbc, encoder).create("Seller");
        ApiClient client = ApiClient.create(context, "198.51.100.250");
        client.login(seller.username(), seller.password()).andExpect(status().isOk());

        client.get("/api/customer-categories").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("Tous")));
        client.post("/api/customer-categories", "{\"name\":\"Nope\"}").andExpect(status().isForbidden());
        client.perform(MockMvcRequestBuilders.put("/api/customer-categories/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Tous\",\"discountPct\":\"50\",\"version\":0}"), true)
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT CAST(discount_pct AS CHAR) FROM customer_categories WHERE id = ?",
                String.class, id)).isEqualTo("0.00");
        client.perform(MockMvcRequestBuilders.delete("/api/customer-categories/" + id), true)
                .andExpect(status().isMethodNotAllowed());
    }

    private ResultActions create(String body) throws Exception {
        return pharmacist.post("/api/customer-categories", body);
    }

    private ResultActions update(long id, String body) throws Exception {
        return pharmacist.perform(MockMvcRequestBuilders.put("/api/customer-categories/" + id)
                .contentType(MediaType.APPLICATION_JSON).content(body), true);
    }

    private static long id(ResultActions actions) throws Exception {
        JsonNode node = JSON.readTree(actions.andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString());
        return node.get("id").asLong();
    }

    private static void assertFieldError(ResultActions actions, String field, String code) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code));
    }
}
