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
import java.math.BigDecimal;
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
 * ADR-175, BR-SALE-018: the global discount (remise globale) of a sales document, a percentage or an amount off the
 * total incl. tax, spread over the lines so HT, VAT and TTC all go down; it needs {@code sale.discount}, is audited,
 * follows a quote into its sale, is what the customer pays, and a return credits it in proportion.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class GlobalDiscountSaleTest {

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
    private BusinessCalendar calendar;

    private TestUsers users;
    private ApiClient pharmacist;
    private long nine;
    private long nineteen;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        TestLots fixtures = new TestLots(jdbc);
        users = new TestUsers(jdbc, encoder);
        pharmacist = client(users.create("Pharmacist"), "198.51.100.231");
        nine = fixtures.product("REMISE-9", "Remise neuf");
        jdbc.update("UPDATE products SET sale_price = 1000 WHERE id = ?", nine);
        fixtures.lot(nine, "LOT-R9", calendar.today().plusYears(1), "50");
        nineteen = fixtures.product("REMISE-19", "Remise dix-neuf");
        jdbc.update("UPDATE products SET sale_price = 500, vat_rate = 19 WHERE id = ?", nineteen);
        fixtures.lot(nineteen, "LOT-R19", calendar.today().plusYears(1), "50");
    }

    @Test
    void aPercentageLowersEachLineAndTheVat() throws Exception {
        long receipt = sale("receipt");
        // 2 × 1,000.00 at 9 % and 1 × 500.00 at 19 %: 2,500.00 excl. tax, 2,775.00 incl. tax
        discount(pharmacist, receipt, "percent", "10").andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.ht").value("2250.00"))
                .andExpect(jsonPath("$.totals.vat").value("247.50"))
                .andExpect(jsonPath("$.totals.ttc").value("2497.50"))
                .andExpect(jsonPath("$.totals.globalDiscount.kind").value("percent"))
                .andExpect(jsonPath("$.totals.globalDiscount.value").value("10.00"))
                .andExpect(jsonPath("$.totals.globalDiscount.ht").value("250.00"))
                .andExpect(jsonPath("$.totals.globalDiscount.ttc").value("277.50"))
                .andExpect(jsonPath("$.lines[0].globalDiscountHt").value("200.00"))
                .andExpect(jsonPath("$.lines[0].lineHt").value("1800.00"))
                .andExpect(jsonPath("$.lines[1].globalDiscountHt").value("50.00"));
        assertStoredAmountsMatch(receipt, "2250.00", "2497.50");
    }

    @Test
    void anAmountComesOffTheTotalInclTaxExactly() throws Exception {
        long receipt = sale("receipt");
        discount(pharmacist, receipt, "amount", "75").andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.ttc").value("2700.00"))
                .andExpect(jsonPath("$.totals.globalDiscount.kind").value("amount"))
                .andExpect(jsonPath("$.totals.globalDiscount.ttc").value("75.00"));
        assertStoredAmountsMatch(receipt, null, "2700.00");

        discount(pharmacist, receipt, null, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.ttc").value("2775.00"))
                .andExpect(jsonPath("$.totals.globalDiscount").doesNotExist())
                .andExpect(jsonPath("$.lines[0].globalDiscountHt").value("0.00"));
    }

    @Test
    void theCustomerPaysTheDiscountedTotalAndAReturnIsCreditedInProportion() throws Exception {
        pharmacist.post("/api/cash-sessions/open", "{\"openingAmount\":\"0\"}").andExpect(status().isCreated());
        long receipt = sale("receipt");
        discount(pharmacist, receipt, "percent", "10").andExpect(status().isOk());
        pharmacist.post("/api/sales/" + receipt + "/confirm", "{\"payments\":[{\"method\":\"cash\",\"amount\":\"2775.00\"}]}")
                .andExpect(status().isUnprocessableContent());
        String confirmed = pharmacist.post("/api/sales/" + receipt + "/confirm",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":\"2497.50\"}]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.netToPay").value("2497.50"))
                .andExpect(jsonPath("$.totals.globalDiscount.ttc").value("277.50"))
                .andReturn().getResponse().getContentAsString();
        long line = JSON.readTree(confirmed).get("lines").get(0).get("id").asLong();
        assertThat(jdbc.queryForObject("SELECT global_discount_ttc FROM sales_documents WHERE id = ?",
                BigDecimal.class, receipt)).isEqualByComparingTo("277.50");

        // one of the two at 9 %: 900.00 excl. tax and 81.00 VAT, what was paid for it
        pharmacist.post("/api/returns/customer", "{\"documentId\":" + receipt + ",\"reason\":\"Erreur\",\"lines\":[{"
                + "\"saleLineId\":" + line + ",\"quantity\":\"1\",\"sealed\":true,\"disposition\":\"restock\"}],"
                + "\"refund\":[{\"method\":\"cash\",\"amount\":\"981.00\"}]}").andExpect(status().isCreated());
        pharmacist.get("/api/cash-sessions/current").andExpect(jsonPath("$.expectedAmount").value("1516.50"));
    }

    @Test
    void aQuoteKeepsItsDiscountThroughItsDeliveryNoteToTheInvoice() throws Exception {
        long quote = sale("quote");
        discount(pharmacist, quote, "percent", "10").andExpect(status().isOk());
        pharmacist.post("/api/sales/" + quote + "/confirm", "{}").andExpect(status().isOk());
        long note = JSON.readTree(pharmacist.post("/api/sales/" + quote + "/convert",
                "{\"targetType\":\"delivery_note\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.totals.globalDiscount.kind").value("percent"))
                .andExpect(jsonPath("$.totals.ttc").value("2497.50"))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        pharmacist.post("/api/sales/" + note + "/confirm", "{}").andExpect(status().isOk());
        long invoice = JSON.readTree(pharmacist.post("/api/sales/" + note + "/convert",
                "{\"targetType\":\"invoice\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.totals.ttc").value("2497.50"))
                .andExpect(jsonPath("$.totals.globalDiscount.ht").value("250.00"))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        discount(pharmacist, invoice, "percent", "20").andExpect(status().isConflict());
    }

    @Test
    void givingOneNeedsSaleDiscountAndIsAudited() throws Exception {
        ApiClient seller = client(users.create("Seller"), "198.51.100.232");
        long receipt = sale("receipt");
        discount(seller, receipt, "percent", "5").andExpect(status().isForbidden());

        discount(pharmacist, receipt, "percent", "5").andExpect(status().isOk());
        discount(pharmacist, receipt, "amount", "100").andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT old_values, new_values FROM audit_log WHERE action = 'DISCOUNT_OVERRIDE'"
                + " AND entity = 'sales_documents' AND entity_id = ? ORDER BY id", receipt))
                .extracting(row -> row.get("new_values").toString())
                .satisfiesExactly(first -> assertThat(first).contains("percent").contains("5.00"),
                        second -> assertThat(second).contains("amount").contains("100.00"));

        discount(seller, receipt, null, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.globalDiscount").doesNotExist());
    }

    @Test
    void outOfRangeValuesAreRefused() throws Exception {
        long receipt = sale("receipt");
        discount(pharmacist, receipt, "percent", "100.01").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("value"));
        discount(pharmacist, receipt, "percent", "0").andExpect(status().isBadRequest());
        discount(pharmacist, receipt, "amount", "2775.01").andExpect(status().isBadRequest());
        discount(pharmacist, receipt, "amount", "1.005").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SCALE"));
        discount(pharmacist, receipt, "coupon", "5").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("kind"));
        discount(pharmacist, receipt, "amount", "2775.00").andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.ttc").value("0.00"));
    }

    @Test
    void anAmountAboveWhatTheLinesLeftIsRefusedAtConfirmation() throws Exception {
        pharmacist.post("/api/cash-sessions/open", "{\"openingAmount\":\"0\"}").andExpect(status().isCreated());
        long receipt = sale("receipt");
        discount(pharmacist, receipt, "amount", "1000").andExpect(status().isOk());
        String detail = pharmacist.get("/api/sales/" + receipt).andReturn().getResponse().getContentAsString();
        long first = JSON.readTree(detail).get("lines").get(0).get("id").asLong();
        pharmacist.perform(MockMvcRequestBuilders.delete("/api/sales/" + receipt + "/lines/" + first), true)
                .andExpect(status().isOk());
        pharmacist.post("/api/sales/" + receipt + "/confirm", "{\"payments\":[{\"method\":\"cash\",\"amount\":\"0\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("globalDiscount"));
    }

    @Test
    void aConfirmedDocumentCannotChangeItsDiscount() throws Exception {
        long quote = sale("quote");
        pharmacist.post("/api/sales/" + quote + "/confirm", "{}").andExpect(status().isOk());
        discount(pharmacist, quote, "percent", "5").andExpect(status().isConflict());
    }

    /** The generated columns compute the same amounts as the service (ADR-016 parity). */
    private void assertStoredAmountsMatch(long document, String ht, String ttc) {
        Map<String, Object> sums = jdbc.queryForMap("SELECT SUM(line_ht) AS ht, SUM(line_ttc) AS ttc"
                + " FROM sales_document_lines WHERE document_id = ?", document);
        Map<String, Object> totals = jdbc.queryForMap("SELECT total_ht, total_ttc FROM sales_documents WHERE id = ?",
                document);
        assertThat((BigDecimal) sums.get("ht")).isEqualByComparingTo((BigDecimal) totals.get("total_ht"));
        assertThat((BigDecimal) sums.get("ttc")).isEqualByComparingTo((BigDecimal) totals.get("total_ttc"))
                .isEqualByComparingTo(ttc);
        if (ht != null) {
            assertThat((BigDecimal) sums.get("ht")).isEqualByComparingTo(ht);
        }
    }

    private ResultActions discount(ApiClient client, long document, String kind, String value) throws Exception {
        String body = kind == null ? "{\"kind\":null,\"value\":null}"
                : "{\"kind\":\"" + kind + "\",\"value\":\"" + value + "\"}";
        return client.perform(MockMvcRequestBuilders.put("/api/sales/" + document + "/global-discount")
                .contentType(MediaType.APPLICATION_JSON).content(body), true);
    }

    private long sale(String type) throws Exception {
        String draft = "receipt".equals(type) ? "{\"docType\":\"receipt\"}"
                : "{\"docType\":\"" + type + "\",\"customerId\":" + customer() + "}";
        long id = JSON.readTree(pharmacist.post("/api/sales", draft).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString()).get("id").asLong();
        pharmacist.post("/api/sales/" + id + "/lines", "{\"productId\":" + nine + ",\"quantity\":\"2\"}")
                .andExpect(status().isOk());
        pharmacist.post("/api/sales/" + id + "/lines", "{\"productId\":" + nineteen + ",\"quantity\":\"1\"}")
                .andExpect(status().isOk());
        return id;
    }

    private long customer() throws Exception {
        return JSON.readTree(pharmacist.post("/api/customers", "{\"name\":\"Ferme Remise " + System.nanoTime() + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private ApiClient client(TestUser user, String ip) throws Exception {
        ApiClient client = ApiClient.create(context, ip);
        client.login(user.username(), user.password()).andExpect(status().isOk());
        return client;
    }
}
