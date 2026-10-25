package com.imsvet.ims.sales;

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
import java.util.Arrays;
import java.util.List;
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
 * Customer prices (FR-SALE-010, BR-CUST-006, ADR-111): per base unit, > 0 with 4 decimals, one per product, set,
 * changed with the version and removed, each audited PRICE_CHANGE (old and new value, product id and amounts only);
 * not for an archived product (existing ones kept and removable); an inactive customer's prices still editable;
 * customer.price.manage to change, customer.read to read.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CustomerPricesTest {

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
    private long customer;
    private long product;

    @BeforeEach
    void data() throws Exception {
        clock.reset();
        pharmacist = client("Pharmacist", "198.51.100.239");
        customer = JSON.readTree(pharmacist.post("/api/customers", "{\"name\":\"Ferme Démo Prix\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        product = new TestLots(jdbc).product("CP-1", "Prix démo");
    }

    @Test
    void aPriceIsSetChangedAndRemovedEachTimeAudited() throws Exception {
        set(product, "\"0.25\"", null).andExpect(status().isOk()).andExpect(jsonPath("$.unitPrice").value("0.2500"))
                .andExpect(jsonPath("$.product.sku").value("CP-1")).andExpect(jsonPath("$.version").value(0));
        set(product, "\"0.2400\"", 0L).andExpect(status().isOk()).andExpect(jsonPath("$.unitPrice").value("0.2400"))
                .andExpect(jsonPath("$.version").value(1));
        pharmacist.get("/api/customers/" + customer + "/prices").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].unitPrice").value("0.2400"));
        remove(product, 1L).andExpect(status().isNoContent());
        pharmacist.get("/api/customers/" + customer + "/prices").andExpect(jsonPath("$.length()").value(0));

        assertThat(jdbc.query("""
                SELECT JSON_EXTRACT(old_values, '$.unitPrice') AS o, JSON_EXTRACT(new_values, '$.unitPrice') AS n,
                       JSON_EXTRACT(new_values, '$.productId') AS p
                FROM audit_log WHERE action = 'PRICE_CHANGE' AND entity = 'customer_product_prices' AND entity_id = ?
                ORDER BY id""", (rs, i) -> Arrays.asList(rs.getString("o"), rs.getString("n"), rs.getString("p")),
                customer)).containsExactly(Arrays.asList("null", "\"0.2500\"", String.valueOf(product)),
                        List.of("\"0.2500\"", "\"0.2400\"", String.valueOf(product)),
                        Arrays.asList("\"0.2400\"", "null", String.valueOf(product)));
    }

    @Test
    void thePriceIsPositiveWithFourDecimalsAndEditedWithTheVersion() throws Exception {
        assertFieldError(set(product, "null", null), "REQUIRED");
        assertFieldError(set(product, "\"0\"", null), "POSITIVE");
        assertFieldError(set(product, "\"-1\"", null), "POSITIVE");
        assertFieldError(set(product, "\"0.12345\"", null), "SCALE");
        set(product, "\"1\"", null).andExpect(status().isOk());
        set(product, "\"2\"", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
        set(product, "\"2\"", 5L).andExpect(status().isConflict());
        remove(product, null).andExpect(status().isConflict());
        remove(999_999L, 0L).andExpect(status().isNotFound());
        set(999_999L, "\"1\"", null).andExpect(status().isNotFound());
    }

    @Test
    void anArchivedProductGetsNoNewPriceButKeepsItsPrice() throws Exception {
        long other = new TestLots(jdbc).product("CP-2", "Archivé démo");
        set(product, "\"1\"", null).andExpect(status().isOk());
        jdbc.update("UPDATE products SET is_archived = TRUE WHERE id IN (?, ?)", product, other);
        entityManager.clear();

        set(other, "\"1\"", null).andExpect(status().is(422)).andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));
        set(product, "\"2\"", 0L).andExpect(status().is(422));
        pharmacist.get("/api/customers/" + customer + "/prices").andExpect(jsonPath("$[0].product.archived").value(true));
        remove(product, 0L).andExpect(status().isNoContent());
    }

    @Test
    void anInactiveCustomersPricesCanStillBeEditedButOnlyWithThePermission() throws Exception {
        pharmacist.post("/api/customers/" + customer + "/deactivate", "").andExpect(status().isOk());
        set(product, "\"1.5\"", null).andExpect(status().isOk());

        ApiClient seller = client("Seller", "198.51.100.238");
        seller.get("/api/customers/" + customer + "/prices").andExpect(status().isOk());
        seller.perform(MockMvcRequestBuilders.put("/api/customers/" + customer + "/prices/" + product)
                .contentType(MediaType.APPLICATION_JSON).content("{\"unitPrice\":\"9\",\"version\":0}"), true)
                .andExpect(status().isForbidden());
        seller.perform(MockMvcRequestBuilders.delete("/api/customers/" + customer + "/prices/" + product
                + "?version=0"), true).andExpect(status().isForbidden());
    }

    private ApiClient client(String role, String ip) throws Exception {
        TestUser user = new TestUsers(jdbc, encoder).create(role);
        ApiClient client = ApiClient.create(context, ip);
        client.login(user.username(), user.password()).andExpect(status().isOk());
        return client;
    }

    private ResultActions set(long productId, String price, Long version) throws Exception {
        return pharmacist.perform(MockMvcRequestBuilders.put("/api/customers/" + customer + "/prices/" + productId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"unitPrice\":" + price + ",\"version\":" + version
                        + "}"), true);
    }

    private ResultActions remove(long productId, Long version) throws Exception {
        return pharmacist.perform(MockMvcRequestBuilders.delete("/api/customers/" + customer + "/prices/" + productId
                + (version == null ? "" : "?version=" + version)), true);
    }

    private static void assertFieldError(ResultActions actions, String code) throws Exception {
        actions.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("unitPrice"))
                .andExpect(jsonPath("$.errors[0].code").value(code));
    }
}
