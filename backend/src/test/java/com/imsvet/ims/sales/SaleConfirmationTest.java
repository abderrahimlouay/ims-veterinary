package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.StockAssertions;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Confirming immediate sales (W2 step 6): numbered at confirmation (ADR-122), sale movements and cost snapshot
 * (BR-SALE-002/009/015), paid in full with the amount kept (ADR-118, BR-SALE-013), re-validation
 * (BR-LOT-003/004, BR-CUST-004, BR-SALE-005/011), idempotency (BR-SALE-016). Credit sales: {@code CreditSaleTest}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SaleConfirmationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 2 × 10.00 HT + 9 % VAT. */
    private static final String TTC = "21.80";

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

    private TestLots fixtures;
    private ApiClient seller;
    private long product;
    private long lot;
    private long receipt;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        today = calendar.today();
        fixtures = new TestLots(jdbc);
        TestUser user = new TestUsers(jdbc, encoder).create("Seller");
        seller = ApiClient.create(context, "198.51.100.201");
        seller.login(user.username(), user.password()).andExpect(status().isOk());
        product = fixtures.product("CONF-1", "Confirmé");
        jdbc.update("UPDATE products SET sale_price = 10 WHERE id = ?", product);
        lot = fixtures.lot(product, "L1", today.plusYears(1), "10");
        receipt = draftWithLine("{\"docType\":\"receipt\"}");
    }

    @Test
    void aCashReceiptIsNumberedMovesStockAndPostsToTheSession() throws Exception {
        long session = openSession("100");
        confirm(receipt, cash(TTC)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"))
                .andExpect(jsonPath("$.number").value(matchesPattern("RCT-\\d{4}-\\d{6}")))
                .andExpect(jsonPath("$.issuedAt").isNotEmpty())
                .andExpect(jsonPath("$.cashSessionId").value(session))
                .andExpect(jsonPath("$.payments[0].method").value("cash"))
                .andExpect(jsonPath("$.payments[0].amount").value(TTC));
        assertThat(balance()).isEqualByComparingTo("8");
        assertThat(jdbc.queryForObject("SELECT quantity FROM stock_movements WHERE reference_type = 'sales_document'"
                + " AND reference_id = ? AND movement_type = 'sale'", BigDecimal.class, receipt))
                .isEqualByComparingTo("-2");
        assertThat(jdbc.queryForObject("SELECT unit_cost FROM sales_document_lines WHERE document_id = ?",
                BigDecimal.class, receipt)).isEqualByComparingTo("0.5");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
        seller.get("/api/cash-sessions/current").andExpect(jsonPath("$.expectedAmount").value("121.80"));
    }

    @Test
    void aCashSaleWithChangeKeepsTheTotalNotTheTendered() throws Exception {
        openSession("100");
        confirm(receipt, "{\"payments\":[{\"method\":\"cash\",\"amount\":\"50.00\"}]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PAYMENT_TOTAL_MISMATCH"));
        confirm(receipt, "{\"tendered\":\"50.00\",\"payments\":[{\"method\":\"cash\",\"amount\":\"" + TTC
                + "\",\"tendered\":\"50.00\"}]}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT m.amount FROM cash_movements m JOIN customer_payments p"
                + " ON p.id = m.customer_payment_id JOIN payment_allocations a ON a.payment_id = p.id"
                + " WHERE a.invoice_id = ?", BigDecimal.class, receipt)).isEqualByComparingTo(TTC);
        seller.get("/api/cash-sessions/current").andExpect(jsonPath("$.expectedAmount").value("121.80"));
    }

    @Test
    void aSplitPaymentMustEqualTheTotalAndChequesNeedNoSession() throws Exception {
        confirm(receipt, cheque("10.00")).andExpect(jsonPath("$.code").value("PAYMENT_TOTAL_MISMATCH"));
        confirm(receipt, "{}").andExpect(jsonPath("$.code").value("PAYMENT_TOTAL_MISMATCH"));
        confirm(receipt, "{\"payments\":[{\"method\":\"cash\"}]}")
                .andExpect(jsonPath("$.errors[0].field").value("payments[0].amount"));
        confirm(receipt, cheque(TTC)).andExpect(status().isOk()).andExpect(jsonPath("$.cashSessionId").isEmpty());
    }

    @Test
    void aCashPartNeedsAnOpenSessionAndNothingIsConfirmedWithoutIt() throws Exception {
        confirm(receipt, cash(TTC)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASH_SESSION_REQUIRED"));
    }

    @Test
    void aDirectInvoiceIsConfirmedWithItsKey() throws Exception {
        long invoice = draftWithLine("{\"docType\":\"invoice\",\"customerId\":" + customer() + "}");
        confirm(invoice, cheque(TTC)).andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(startsWith("INV-")));
        assertThat(balance()).isEqualByComparingTo("8");
    }

    @Test
    void quotesAndDeliveryNotesWaitForTheConversions() throws Exception {
        long customer = customer();
        long note = draftWithLine("{\"docType\":\"delivery_note\",\"customerId\":" + customer + "}");
        confirm(note, "{}").andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        assertThat(balance()).isEqualByComparingTo("10");
    }

    @Test
    void anUnsellableLotIsRefusedAtConfirmation() throws Exception {
        for (String[] c : new String[][] {{"blocked", "LOT_BLOCKED"}, {"recalled", "LOT_RECALLED"}}) {
            jdbc.update("UPDATE lots SET status = ? WHERE id = ?", c[0], lot);
            entities.clear();
            confirm(receipt, cheque(TTC)).andExpect(jsonPath("$.code").value(c[1]));
        }
        jdbc.update("UPDATE lots SET status = 'active', expiry_date = ? WHERE id = ?", today.minusDays(1), lot);
        entities.clear();
        confirm(receipt, cheque(TTC)).andExpect(jsonPath("$.code").value("LOT_EXPIRED"));
    }

    @Test
    void stockThatLeftMeanwhileIsRefusedAndALastDayLotIsSold() throws Exception {
        jdbc.update("UPDATE lots SET expiry_date = ? WHERE id = ?", today, lot);
        long other = draftWithLine("{\"docType\":\"receipt\"}");
        jdbc.update("UPDATE sales_document_lines SET quantity = 11, quantity_base = 11 WHERE document_id = ?", other);
        jdbc.update("UPDATE sales_documents SET total_ttc = 0 WHERE id = ?", other);
        entities.clear();
        confirm(other, cheque("119.90")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"));
        entities.clear();
        confirm(receipt, cheque(TTC)).andExpect(status().isOk());
    }

    @Test
    void inactiveCustomersArchivedProductsAndEmptyDocumentsAreRefused() throws Exception {
        long customer = customer();
        long invoice = draftWithLine("{\"docType\":\"invoice\",\"customerId\":" + customer + "}");
        jdbc.update("UPDATE customers SET is_active = FALSE WHERE id = ?", customer);
        entities.clear();
        confirm(invoice, cheque(TTC)).andExpect(jsonPath("$.code").value("CUSTOMER_INACTIVE"));
        jdbc.update("UPDATE products SET is_archived = TRUE WHERE id = ?", product);
        entities.clear();
        confirm(receipt, cheque(TTC)).andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));
        long empty = JSON.readTree(seller.post("/api/sales", "{\"docType\":\"receipt\"}")
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        confirm(empty, "{}").andExpect(jsonPath("$.errors[0].field").value("lines"));
    }

    @Test
    void aLineWithoutALotIsRefused() throws Exception {
        jdbc.update("UPDATE sales_document_lines SET lot_id = NULL WHERE document_id = ?", receipt);
        entities.clear();
        confirm(receipt, cheque(TTC)).andExpect(jsonPath("$.code").value("LOT_REQUIRED"));
    }

    @Test
    void aSecondConfirmationPostsNothingMore() throws Exception {
        confirm(receipt, cheque(TTC)).andExpect(status().isOk());
        entities.clear();
        confirm(receipt, cheque(TTC)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        assertThat(balance()).isEqualByComparingTo("8");
    }

    @Test
    void theSameKeyReturnsTheFirstResultAndAnotherBodyConflicts() throws Exception {
        String key = UUID.randomUUID().toString();
        String number = JSON.readTree(confirmWithKey(receipt, cheque(TTC), key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("number").asString();
        entities.clear();
        confirmWithKey(receipt, cheque(TTC), key).andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(number));
        assertThat(balance()).isEqualByComparingTo("8");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_payments p JOIN payment_allocations a"
                + " ON a.payment_id = p.id WHERE a.invoice_id = ?", Long.class, receipt)).isOne();
        confirmWithKey(receipt, cheque("1.00"), key).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void aConfirmedDocumentIsImmutableAndNeedsThePermission() throws Exception {
        confirm(receipt, cheque(TTC)).andExpect(status().isOk());
        entities.clear();
        seller.post("/api/sales/" + receipt + "/lines", "{\"productId\":" + product + ",\"quantity\":\"1\"}")
                .andExpect(jsonPath("$.code").value("DOCUMENT_IMMUTABLE"));
        TestUser user = new TestUsers(jdbc, encoder).create("Accountant");
        ApiClient accountant = ApiClient.create(context, "198.51.100.202");
        accountant.login(user.username(), user.password()).andExpect(status().isOk());
        confirmAs(accountant, receipt, "{}").andExpect(status().isForbidden());
    }

    private long draftWithLine(String json) throws Exception {
        long id = JSON.readTree(seller.post("/api/sales", json).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        seller.post("/api/sales/" + id + "/lines", "{\"productId\":" + product + ",\"quantity\":\"2\"}")
                .andExpect(status().isOk());
        return id;
    }

    private long customer() throws Exception {
        return JSON.readTree(seller.post("/api/customers", "{\"name\":\"Ferme Démo " + System.nanoTime() + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private long openSession(String amount) throws Exception {
        return JSON.readTree(seller.post("/api/cash-sessions/open", "{\"openingAmount\":\"" + amount + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private ResultActions confirm(long id, String json) throws Exception {
        return confirmAs(seller, id, json);
    }

    private static ResultActions confirmAs(ApiClient client, long id, String json) throws Exception {
        return client.post("/api/sales/" + id + "/confirm", json);
    }

    private ResultActions confirmWithKey(long id, String json, String key) throws Exception {
        return seller.perform(post("/api/sales/" + id + "/confirm").contentType(MediaType.APPLICATION_JSON)
                .content(json).header("Idempotency-Key", key), true);
    }

    private BigDecimal balance() {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", BigDecimal.class, lot);
    }

    private static String cash(String amount) {
        return "{\"payments\":[{\"method\":\"cash\",\"amount\":\"" + amount + "\"}]}";
    }

    private static String cheque(String amount) {
        return "{\"payments\":[{\"method\":\"cheque\",\"amount\":\"" + amount + "\",\"reference\":\"CHQ-1\"}]}";
    }
}
