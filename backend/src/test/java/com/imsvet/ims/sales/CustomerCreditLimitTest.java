package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
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
 * The credit limit (BR-CUST-002, ADR-108): changed only through its endpoint with customer.credit.manage (an
 * Accountant, not a Seller nor a Pharmacist), with the version; NULL = no limit, 0 = cash only, ≥ 0 with 2 decimals;
 * a missing field is refused; every change, to and from NULL, audited CREDIT_LIMIT_CHANGE with old and new value
 * and amounts only.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CustomerCreditLimitTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    private ApiClient accountant;
    private long customer;

    @BeforeEach
    void customer() throws Exception {
        clock.reset();
        ApiClient seller = client("Seller", "198.51.100.254");
        customer = JSON.readTree(seller.post("/api/customers", "{\"name\":\"Ferme Démo Crédit\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        accountant = client("Accountant", "198.51.100.243");
    }

    @Test
    void everyChangeOfTheLimitIsAuditedWithOldAndNewValueAlsoToAndFromNoLimit() throws Exception {
        change(accountant, "\"5000\"", 0).andExpect(status().isOk()).andExpect(jsonPath("$.creditLimit").value("5000.00"))
                .andExpect(jsonPath("$.version").value(1));
        change(accountant, "null", 1).andExpect(status().isOk()).andExpect(jsonPath("$.creditLimit").isEmpty());
        change(accountant, "\"1200.5\"", 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.creditLimit").value("1200.50"));
        change(accountant, "\"1200.50\"", 3).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3));
        change(accountant, "\"0\"", 3).andExpect(status().isOk()).andExpect(jsonPath("$.creditLimit").value("0.00"));

        assertThat(jdbc.query("""
                SELECT JSON_EXTRACT(old_values, '$.creditLimit') AS o, JSON_EXTRACT(new_values, '$.creditLimit') AS n,
                       JSON_KEYS(new_values) AS k
                FROM audit_log WHERE action = 'CREDIT_LIMIT_CHANGE' AND entity = 'customers' AND entity_id = ? ORDER BY id""",
                (rs, i) -> Arrays.asList(rs.getString("o"), rs.getString("n"), rs.getString("k")), customer))
                .containsExactly(List.of("\"0.00\"", "\"5000.00\"", "[\"creditLimit\"]"),
                        Arrays.asList("\"5000.00\"", "null", "[\"creditLimit\"]"),
                        Arrays.asList("null", "\"1200.50\"", "[\"creditLimit\"]"),
                        List.of("\"1200.50\"", "\"0.00\"", "[\"creditLimit\"]"));
    }

    @Test
    void theLimitIsValidatedAndChangedWithTheCurrentVersion() throws Exception {
        assertFieldError(change(accountant, "\"-1\"", 0), "NOT_NEGATIVE");
        assertFieldError(change(accountant, "\"10.001\"", 0), "SCALE");
        assertFieldError(change(accountant, "\"abc\"", 0), "FORMAT");
        assertFieldError(accountant.perform(MockMvcRequestBuilders.put("/api/customers/" + customer + "/credit-limit")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"), true), "REQUIRED");
        change(accountant, "\"10\"", 7).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
        change(accountant, "\"10\"", 0).andExpect(status().isOk());
        accountant.perform(MockMvcRequestBuilders.put("/api/customers/999999/credit-limit")
                .contentType(MediaType.APPLICATION_JSON).content("{\"creditLimit\":\"1\",\"version\":0}"), true)
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyCustomerCreditManageChangesTheLimit() throws Exception {
        change(client("Seller", "198.51.100.241"), "\"100\"", 0).andExpect(status().isForbidden());
        change(client("Pharmacist", "198.51.100.240"), "\"100\"", 0).andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT CAST(credit_limit AS CHAR) FROM customers WHERE id = ?", String.class,
                customer)).isEqualTo("0.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'CREDIT_LIMIT_CHANGE' "
                + "AND entity_id = ?", Integer.class, customer)).isZero();
    }

    private ApiClient client(String role, String ip) throws Exception {
        TestUser user = new TestUsers(jdbc, encoder).create(role);
        ApiClient client = ApiClient.create(context, ip);
        client.login(user.username(), user.password()).andExpect(status().isOk());
        return client;
    }

    private ResultActions change(ApiClient client, String limit, long version) throws Exception {
        return client.perform(MockMvcRequestBuilders.put("/api/customers/" + customer + "/credit-limit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"creditLimit\":" + limit + ",\"version\":" + version + "}"), true);
    }

    private static void assertFieldError(ResultActions actions, String code) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("creditLimit"))
                .andExpect(jsonPath("$.errors[0].code").value(code));
    }
}
