package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-172: the stamp duty of an immediate invoice or receipt on its cash part, off by default, frozen on the document,
 * kept with the cash payment and in the session's expected cash; never on cheques, credit sales or credit notes.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class StampDutySaleTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 2 × 1,000.00 HT + 9 % VAT. */
    private static final String TTC = "2180.00";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestClock clock;

    @Autowired
    private BusinessCalendar calendar;

    @PersistenceContext
    private EntityManager entities;

    private ApiClient seller;
    private long product;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        TestLots fixtures = new TestLots(jdbc);
        TestUser user = new TestUsers(jdbc, encoder).create("Pharmacist");
        seller = ApiClient.create(context, "198.51.100.241");
        seller.login(user.username(), user.password()).andExpect(status().isOk());
        product = fixtures.product("TIMBRE-1", "Timbre Démo");
        jdbc.update("UPDATE products SET sale_price = 1000 WHERE id = ?", product);
        fixtures.lot(product, "LOT-T1", calendar.today().plusYears(1), "50");
    }

    @Test
    void offByDefaultNothingIsCharged() throws Exception {
        seller.post("/api/cash-sessions/open", "{\"openingAmount\":\"0\"}").andExpect(status().isCreated());
        long receipt = sale("{\"docType\":\"receipt\"}");
        seller.get("/api/sales/" + receipt + "/stamp-duty").andExpect(status().isOk())
                .andExpect(jsonPath("$.stampDuty").value("0.00")).andExpect(jsonPath("$.netToPay").value(TTC));
        seller.post("/api/sales/" + receipt + "/confirm", cash(TTC)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.stampDuty").value("0.00"))
                .andExpect(jsonPath("$.totals.netToPay").value(TTC))
                .andExpect(jsonPath("$.payments[0].amount").value(TTC));
    }

    @Test
    void aCashReceiptCarriesItInThePaymentTheSessionAndTheDocument() throws Exception {
        scale();
        seller.post("/api/cash-sessions/open", "{\"openingAmount\":\"100\"}").andExpect(status().isCreated());
        long receipt = sale("{\"docType\":\"receipt\"}");
        seller.get("/api/sales/" + receipt + "/stamp-duty").andExpect(status().isOk())
                .andExpect(jsonPath("$.cashPart").value(TTC)).andExpect(jsonPath("$.stampDuty").value("22.00"))
                .andExpect(jsonPath("$.netToPay").value("2202.00"));

        seller.post("/api/sales/" + receipt + "/confirm", cash(TTC)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.ttc").value(TTC))
                .andExpect(jsonPath("$.totals.stampDuty").value("22.00"))
                .andExpect(jsonPath("$.totals.netToPay").value("2202.00"))
                .andExpect(jsonPath("$.payments[0].amount").value("2202.00"));
        seller.get("/api/cash-sessions/current").andExpect(jsonPath("$.expectedAmount").value("2302.00"));
        assertThat(jdbc.queryForObject("SELECT net_to_pay FROM sales_documents WHERE id = ?", BigDecimal.class,
                receipt)).isEqualByComparingTo("2202.00");
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM payment_allocations WHERE invoice_id = ?",
                BigDecimal.class, receipt)).isEqualByComparingTo("2202.00");
    }

    @Test
    void onlyTheCashPartOfASplitPaymentIsStamped() throws Exception {
        scale();
        seller.post("/api/cash-sessions/open", "{\"openingAmount\":\"0\"}").andExpect(status().isCreated());
        long invoice = sale("{\"docType\":\"invoice\",\"customerId\":" + customer() + "}");
        seller.get("/api/sales/" + invoice + "/stamp-duty?cash=1000.00").andExpect(status().isOk())
                .andExpect(jsonPath("$.stampDuty").value("10.00")).andExpect(jsonPath("$.netToPay").value("2190.00"));

        seller.post("/api/sales/" + invoice + "/confirm", "{\"payments\":[{\"method\":\"cheque\",\"amount\":\"1180.00\","
                + "\"reference\":\"CHQ-9\"},{\"method\":\"cash\",\"amount\":\"1000.00\"}]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.stampDuty").value("10.00"))
                .andExpect(jsonPath("$.totals.netToPay").value("2190.00"))
                .andExpect(jsonPath("$.payments[0].method").value("cheque"))
                .andExpect(jsonPath("$.payments[0].amount").value("1180.00"))
                .andExpect(jsonPath("$.payments[1].amount").value("1010.00"));
        seller.get("/api/cash-sessions/current").andExpect(jsonPath("$.expectedAmount").value("1010.00"));
    }

    @Test
    void chequesCreditSalesAndExcludedReceiptsCarryNone() throws Exception {
        scale();
        long byCheque = sale("{\"docType\":\"receipt\"}");
        seller.post("/api/sales/" + byCheque + "/confirm", "{\"payments\":[{\"method\":\"cheque\",\"amount\":\"" + TTC
                + "\",\"reference\":\"CHQ-1\"}]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.stampDuty").value("0.00"));

        long customer = customer();
        jdbc.update("UPDATE customers SET credit_limit = NULL WHERE id = ?", customer);
        long credit = sale("{\"docType\":\"invoice\",\"customerId\":" + customer + ",\"creditSale\":true}");
        seller.get("/api/sales/" + credit + "/stamp-duty").andExpect(jsonPath("$.stampDuty").value("0.00"));
        seller.post("/api/sales/" + credit + "/confirm", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.stampDuty").value("0.00"));

        setting("stamp_duty_on_receipts", "false");
        seller.post("/api/cash-sessions/open", "{\"openingAmount\":\"0\"}").andExpect(status().isCreated());
        long receipt = sale("{\"docType\":\"receipt\"}");
        seller.post("/api/sales/" + receipt + "/confirm", cash(TTC)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.stampDuty").value("0.00"));
        long invoice = sale("{\"docType\":\"invoice\",\"customerId\":" + customer() + "}");
        seller.post("/api/sales/" + invoice + "/confirm", cash(TTC)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.stampDuty").value("22.00"));
    }

    @Test
    void aConfirmedDocumentKeepsItsStampDutyWhenTheSettingsChange() throws Exception {
        scale();
        seller.post("/api/cash-sessions/open", "{\"openingAmount\":\"0\"}").andExpect(status().isCreated());
        long receipt = sale("{\"docType\":\"receipt\"}");
        seller.post("/api/sales/" + receipt + "/confirm", cash(TTC)).andExpect(status().isOk());
        setting("stamp_duty_rate", "off");
        seller.get("/api/sales/" + receipt).andExpect(jsonPath("$.totals.stampDuty").value("22.00"));
        seller.get("/api/sales/" + receipt + "/stamp-duty").andExpect(jsonPath("$.stampDuty").value("22.00"));
    }

    @Test
    void aReturnNeverRefundsTheStampDuty() throws Exception {
        scale();
        seller.post("/api/cash-sessions/open", "{\"openingAmount\":\"0\"}").andExpect(status().isCreated());
        long receipt = sale("{\"docType\":\"receipt\"}");
        String confirmed = seller.post("/api/sales/" + receipt + "/confirm", cash(TTC)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long line = JSON.readTree(confirmed).get("lines").get(0).get("id").asLong();
        seller.post("/api/returns/customer", "{\"documentId\":" + receipt + ",\"reason\":\"Erreur\",\"lines\":[{"
                + "\"saleLineId\":" + line + ",\"quantity\":\"2\",\"sealed\":true,\"disposition\":\"restock\"}],"
                + "\"refund\":[{\"method\":\"cash\",\"amount\":\"" + TTC + "\"}]}").andExpect(status().isCreated());
        seller.get("/api/cash-sessions/current").andExpect(jsonPath("$.expectedAmount").value("22.00"));
    }

    @Test
    void aCashPartOutsideTheTotalIsRefused() throws Exception {
        long receipt = sale("{\"docType\":\"receipt\"}");
        seller.get("/api/sales/" + receipt + "/stamp-duty?cash=2180.01").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("cash"));
        seller.get("/api/sales/" + receipt + "/stamp-duty?cash=-1").andExpect(status().isBadRequest());
    }

    /** 1 % per started 100.00, no minimum, maximum or threshold. */
    private void scale() {
        setting("stamp_duty_rate", "1.00");
        setting("stamp_duty_step", "100.00");
    }

    /** Written behind JPA's back, so the cached settings of this test's transaction are dropped. */
    private void setting(String key, String value) {
        jdbc.update("UPDATE app_settings SET setting_value = ? WHERE setting_key = ?", value, key);
        entities.clear();
    }

    private long sale(String draft) throws Exception {
        long id = JSON.readTree(seller.post("/api/sales", draft).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString()).get("id").asLong();
        seller.post("/api/sales/" + id + "/lines", "{\"productId\":" + product + ",\"quantity\":\"2\"}")
                .andExpect(status().isOk());
        return id;
    }

    private long customer() throws Exception {
        return JSON.readTree(seller.post("/api/customers", "{\"name\":\"Ferme Timbre " + System.nanoTime() + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private static String cash(String amount) {
        return "{\"payments\":[{\"method\":\"cash\",\"amount\":\"" + amount + "\"}]}";
    }
}
