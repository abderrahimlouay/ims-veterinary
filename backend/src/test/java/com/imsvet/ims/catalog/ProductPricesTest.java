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
import tools.jackson.databind.json.JsonMapper;

/**
 * BR-PROD-006 and ADR-049 over the API: prices per base unit with 4 decimals; setting or changing a price
 * needs {@code price.edit}; every change after creation is audited {@code PRICE_CHANGE} with old and new
 * values; an omitted or unchanged price changes and audits nothing.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ProductPricesTest {

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

    private TestUser user;
    private ApiClient pharmacist;

    @BeforeEach
    void logIn() throws Exception {
        clock.reset();
        user = new TestUsers(jdbc, encoder).create("Pharmacist");
        pharmacist = ApiClient.create(context, "198.51.100.130");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void pricesAreSetAtCreationWithFourDecimalsAndNotAudited() throws Exception {
        long id = idOf(create("P-1", "\"salePrice\":\"0.0125\",\"purchasePrice\":\"0.01\"")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.salePrice").value("0.0125"))
                .andExpect(jsonPath("$.purchasePrice").value("0.0100")));

        assertThat(priceAudits(id)).isZero();
    }

    @Test
    void aPriceChangeIsAuditedWithOldAndNewValuesOfTheChangedPriceOnly() throws Exception {
        long id = idOf(create("P-2", "\"salePrice\":\"0.0125\",\"purchasePrice\":\"0.0100\""));

        update(id, 0, "\"salePrice\":\"0.015\",\"purchasePrice\":\"0.0100\"").andExpect(status().isOk())
                .andExpect(jsonPath("$.salePrice").value("0.0150"));

        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT user_id, old_values->>'$.salePrice' AS old_sale, new_values->>'$.salePrice' AS new_sale,
                       JSON_CONTAINS_PATH(new_values, 'one', '$.purchasePrice') AS has_purchase
                FROM audit_log WHERE action = 'PRICE_CHANGE' AND entity = 'products' AND entity_id = ?""", id);
        assertThat(audit).containsEntry("user_id", user.id()).containsEntry("old_sale", "0.0125")
                .containsEntry("new_sale", "0.0150").containsEntry("has_purchase", 0L);
    }

    @Test
    void omittedOrUnchangedPricesChangeAndAuditNothing() throws Exception {
        long id = idOf(create("P-3", "\"salePrice\":\"2.5\""));

        update(id, 0, "\"commercialName\":\"Renamed\"").andExpect(status().isOk())
                .andExpect(jsonPath("$.salePrice").value("2.5000"));
        update(id, 1, "\"salePrice\":\"2.50\"").andExpect(status().isOk());

        assertThat(priceAudits(id)).isZero();
    }

    @Test
    void withoutPriceEditPricesCanBeNeitherSetNorChanged() throws Exception {
        long id = idOf(create("P-4", "\"salePrice\":\"3\""));
        jdbc.update("""
                DELETE rp FROM role_permissions rp JOIN roles r ON r.id = rp.role_id
                JOIN permissions p ON p.id = rp.permission_id WHERE r.name = 'Pharmacist' AND p.code = 'price.edit'""");
        entityManager.flush();
        entityManager.clear();

        create("P-5", "\"salePrice\":\"1\"").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        create("P-6", "\"salePrice\":\"0\"").andExpect(status().isCreated());
        update(id, 0, "\"commercialName\":\"Name only\"").andExpect(status().isOk());
        update(id, 1, "\"salePrice\":\"3.0000\"").andExpect(status().isOk());
        update(id, 2, "\"salePrice\":\"4\"").andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT sale_price FROM products WHERE id = ?", String.class, id))
                .isEqualTo("3.0000");
        assertThat(priceAudits(id)).isZero();
    }

    @Test
    void invalidPricesAreFieldErrors() throws Exception {
        create("P-7", "\"salePrice\":\"-1\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("salePrice"))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_NEGATIVE"));
        create("P-8", "\"purchasePrice\":\"0.00001\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("purchasePrice"))
                .andExpect(jsonPath("$.errors[0].code").value("SCALE"));
    }

    private ResultActions create(String sku, String prices) throws Exception {
        return pharmacist.post("/api/products", "{\"sku\":\"" + sku + "\",\"commercialName\":\"Priced\","
                + "\"baseUnit\":\"tablet\",\"divisible\":false," + prices + "}");
    }

    /** {@code fields} replace the defaults below (later JSON keys win). */
    private ResultActions update(long id, long version, String fields) throws Exception {
        String body = "{\"sku\":\"U-" + id + "\",\"commercialName\":\"Priced\",\"baseUnit\":\"tablet\","
                + "\"divisible\":false,\"vatRate\":\"9\",\"version\":" + version + "," + fields + "}";
        return pharmacist.perform(MockMvcRequestBuilders.put("/api/products/" + id)
                .contentType(MediaType.APPLICATION_JSON).content(body), true);
    }

    private int priceAudits(long id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'PRICE_CHANGE' AND entity_id = ?", Integer.class, id);
    }

    private static long idOf(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
