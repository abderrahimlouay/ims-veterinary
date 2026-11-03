package com.imsvet.ims.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cash in Phase 9 (ADR-136, W9, BR-CASH-006/007): a manual movement needs {@code cash.manage}, a label and the user's own
 * open session, and an OUT never beyond the expected cash; it is audited. A manager closes another user's session and
 * the variance audit names both. The session report totals the cash by source, lists the cheques and transfers apart,
 * and is open to {@code cash.manage} or the session's cashier.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CashManagementTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    private ApiClient accountant;
    private ApiClient seller;
    private TestUser accountantUser;
    private TestUser sellerUser;

    @BeforeEach
    void setUp() throws Exception {
        accountantUser = new TestUsers(jdbc, encoder).create("Accountant");
        accountant = ApiClient.create(context, "198.51.100.161");
        accountant.login(accountantUser.username(), accountantUser.password()).andExpect(status().isOk());
        sellerUser = new TestUsers(jdbc, encoder).create("Seller");
        seller = ApiClient.create(context, "198.51.100.162");
        seller.login(sellerUser.username(), sellerUser.password()).andExpect(status().isOk());
    }

    @Test
    void aManualMovementNeedsALabelTheUsersOwnSessionAndNeverOverdrawsIt() throws Exception {
        long own = open(accountant, "20");
        long others = open(seller, "50");

        move(accountant, own, "{\"direction\":\"in\",\"amount\":\"5\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("label"));
        move(accountant, own, "{\"direction\":\"sideways\",\"amount\":\"5\",\"label\":\"x\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("UNKNOWN"));
        move(accountant, others, "{\"direction\":\"in\",\"amount\":\"5\",\"label\":\"Fonds\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASH_SESSION_REQUIRED"));
        move(accountant, own, "{\"direction\":\"out\",\"amount\":\"20.01\",\"label\":\"Achat timbres\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("CASH_INSUFFICIENT"));
        move(seller, others, "{\"direction\":\"in\",\"amount\":\"5\",\"label\":\"Fonds\"}")
                .andExpect(status().isForbidden());

        move(accountant, own, "{\"direction\":\"out\",\"amount\":\"12.50\",\"label\":\" Achat timbres \"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.expectedAmount").value("7.50"));
        move(accountant, own, "{\"direction\":\"in\",\"amount\":\"2.50\",\"label\":\"Appoint\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.expectedAmount").value("10.00"));

        assertThat(jdbc.queryForObject("SELECT label FROM cash_movements WHERE cash_session_id = ? AND direction = 'out'",
                String.class, own)).isEqualTo("Achat timbres");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'CASH_MOVEMENT_CREATE'"
                + " AND new_values LIKE ?", Integer.class, "%\"cashSessionId\": " + own + "%")).isEqualTo(2);
        accountant.post("/api/cash-sessions/" + own + "/close", "{\"countedAmount\":\"10\"}").andExpect(status().isOk());
        move(accountant, own, "{\"direction\":\"in\",\"amount\":\"1\",\"label\":\"Tard\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASH_SESSION_CLOSED"));
    }

    @Test
    void aManagerClosesAnotherUsersSessionAndTheVarianceAuditNamesBoth() throws Exception {
        long others = open(seller, "50");
        seller.post("/api/cash-sessions/" + open(accountant, "0") + "/close", "{\"countedAmount\":\"0\"}")
                .andExpect(status().isForbidden());

        accountant.post("/api/cash-sessions/" + others + "/close", "{\"countedAmount\":\"48\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.varianceAmount").value("-2.00"));

        assertThat(jdbc.queryForObject("SELECT closed_by FROM cash_sessions WHERE id = ?", Long.class, others))
                .isEqualTo(accountantUser.id());
        String audit = jdbc.queryForObject("SELECT CONCAT(old_values, new_values) FROM audit_log"
                + " WHERE action = 'CASH_VARIANCE' AND entity_id = ?", String.class, others);
        assertThat(audit).contains("\"cashierId\": " + sellerUser.id()).contains("\"closedBy\": " + accountantUser.id());
    }

    @Test
    void theReportTotalsTheCashBySourceAndListsChequesAndTransfersApart() throws Exception {
        long session = open(accountant, "100");
        long customer = customer();
        long supplier = supplier();
        accountant.post("/api/payments/customers", "{\"customerId\":" + customer
                + ",\"method\":\"cash\",\"amount\":\"30\",\"allocations\":[]}").andExpect(status().isCreated());
        accountant.post("/api/payments/customers", "{\"customerId\":" + customer
                + ",\"method\":\"cheque\",\"amount\":\"70\",\"reference\":\"CHQ-9\",\"allocations\":[]}")
                .andExpect(status().isCreated());
        long paid = id(accountant.post("/api/payments/suppliers", "{\"supplierId\":" + supplier
                + ",\"method\":\"cash\",\"amount\":\"40\",\"allocations\":[]}").andExpect(status().isCreated()));
        accountant.post("/api/payments/suppliers/" + paid + "/reverse", "{\"reason\":\"Erreur\"}")
                .andExpect(status().isOk());
        move(accountant, session, "{\"direction\":\"out\",\"amount\":\"5\",\"label\":\"Timbres\"}")
                .andExpect(status().isCreated());

        accountant.get("/api/cash-sessions/" + session + "/report").andExpect(status().isOk())
                .andExpect(jsonPath("$.openingAmount").value("100.00"))
                .andExpect(jsonPath("$.bySource[0].source").value("sales"))
                .andExpect(jsonPath("$.bySource[1].source").value("account_payments"))
                .andExpect(jsonPath("$.bySource[1].in").value("30.00"))
                .andExpect(jsonPath("$.bySource[3].source").value("supplier_payments"))
                .andExpect(jsonPath("$.bySource[3].out").value("40.00"))
                .andExpect(jsonPath("$.bySource[4].source").value("reversals"))
                .andExpect(jsonPath("$.bySource[4].in").value("40.00"))
                .andExpect(jsonPath("$.bySource[5].source").value("manual"))
                .andExpect(jsonPath("$.bySource[5].out").value("5.00"))
                .andExpect(jsonPath("$.expected").value("125.00"))
                .andExpect(jsonPath("$.nonCash.length()").value(1))
                .andExpect(jsonPath("$.nonCash[0].kind").value("customer_payment"))
                .andExpect(jsonPath("$.nonCash[0].reference").value("CHQ-9"))
                .andExpect(jsonPath("$.nonCash[0].direction").value("in"));
        seller.get("/api/cash-sessions/" + session + "/report").andExpect(status().isForbidden());
        long sellers = open(seller, "10");
        seller.get("/api/cash-sessions/" + sellers + "/report").andExpect(status().isOk())
                .andExpect(jsonPath("$.expected").value("10.00"));
    }

    private long open(ApiClient client, String amount) throws Exception {
        return id(client.post("/api/cash-sessions/open", "{\"openingAmount\":\"" + amount + "\"}")
                .andExpect(status().isCreated()));
    }

    private ResultActions move(ApiClient client, long session, String body) throws Exception {
        return client.post("/api/cash-sessions/" + session + "/movements", body);
    }

    private long customer() {
        jdbc.update("INSERT INTO customers (name, created_at, updated_at) VALUES (?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                "Ferme Caisse " + System.nanoTime());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long supplier() {
        String name = "Labo Caisse " + System.nanoTime();
        jdbc.update("INSERT INTO suppliers (name, created_at, updated_at) VALUES (?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                name);
        return jdbc.queryForObject("SELECT id FROM suppliers WHERE name = ?", Long.class, name);
    }

    private static long id(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
