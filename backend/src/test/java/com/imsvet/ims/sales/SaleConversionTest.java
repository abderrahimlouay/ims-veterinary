package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * Quotes, delivery notes and conversions (W11): a quote moves nothing (BR-SALE-003), a delivery note moves stock and takes
 * no payment, one whole note gives one invoice with its lines, lots and prices frozen, no second movement and no new limit
 * check (BR-SALE-001/002, ADR-117, ADR-125).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SaleConversionTest {

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
    private long customer;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        today = calendar.today();
        fixtures = new TestLots(jdbc);
        TestUser user = new TestUsers(jdbc, encoder).create("Seller");
        seller = ApiClient.create(context, "198.51.100.221");
        seller.login(user.username(), user.password()).andExpect(status().isOk());
        product = fixtures.product("CONV-1", "Converti");
        jdbc.update("UPDATE products SET sale_price = 10 WHERE id = ?", product);
        lot = fixtures.lot(product, "L1", today.plusYears(1), "10");
        customer = id(seller.post("/api/customers", "{\"name\":\"Ferme Conversion " + System.nanoTime() + "\"}")
                .andExpect(status().isCreated()));
    }

    @Test
    void aQuoteIsNumberedAndMovesNothing() throws Exception {
        long quote = draft("quote", false);
        confirm(quote, "{}").andExpect(status().isOk()).andExpect(jsonPath("$.number").value(startsWith("QUO-")))
                .andExpect(jsonPath("$.lines[0].lotId").isEmpty());
        assertThat(onHand()).isEqualByComparingTo("10");
        assertThat(movements(quote)).isZero();
        confirm(draft("quote", false), cheque(TTC)).andExpect(jsonPath("$.code").value("PAYMENT_TOTAL_MISMATCH"));
    }

    @Test
    void aCreditQuotePassesNoLimitCheck() throws Exception {
        jdbc.update("UPDATE customers SET credit_limit = 0 WHERE id = ?", customer);
        entities.clear();
        confirm(draft("quote", true), "{}").andExpect(status().isOk());
    }

    @Test
    void aDeliveryNoteMovesStockAndTakesNoPayment() throws Exception {
        long note = draft("delivery_note", false);
        confirm(note, cheque(TTC)).andExpect(jsonPath("$.code").value("PAYMENT_TOTAL_MISMATCH"));
        confirm(note, "{}").andExpect(status().isOk()).andExpect(jsonPath("$.number").value(startsWith("DN-")))
                .andExpect(jsonPath("$.payments").isEmpty());
        assertThat(onHand()).isEqualByComparingTo("8");
        assertThat(movements(note)).isOne();
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
    }

    @Test
    void aCreditDeliveryNotePassesTheLimitCheck() throws Exception {
        jdbc.update("UPDATE customers SET credit_limit = 21.79 WHERE id = ?", customer);
        entities.clear();
        long note = draft("delivery_note", true);
        confirm(note, "{}").andExpect(jsonPath("$.code").value("CREDIT_LIMIT_EXCEEDED"));
        assertThat(onHand()).isEqualByComparingTo("10");
        limit("21.80");
        confirm(note, "{}").andExpect(status().isOk()).andExpect(jsonPath("$.dueDate").isEmpty());
        seller.get("/api/customers/" + customer + "/balance").andExpect(jsonPath("$.balance").value("0.00"))
                .andExpect(jsonPath("$.uninvoicedDeliveryNotes").value(TTC));
    }

    @Test
    void aDeliveryNoteInvoiceFreezesItsLinesAndPostsNothing() throws Exception {
        long note = confirmedNote(false);
        BigDecimal cost = jdbc.queryForObject("SELECT unit_cost FROM sales_document_lines WHERE document_id = ?",
                BigDecimal.class, note);
        jdbc.update("UPDATE products SET sale_price = 99 WHERE id = ?", product);
        entities.clear();
        String body = convert(note, "invoice").andExpect(status().isCreated())
                .andExpect(jsonPath("$.docType").value("invoice"))
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.number").isEmpty())
                .andExpect(jsonPath("$.parentId").value(note))
                .andExpect(jsonPath("$.lines[0].lotId").value(lot))
                .andExpect(jsonPath("$.lines[0].unitPrice").value("10.0000"))
                .andExpect(jsonPath("$.totals.ttc").value(TTC))
                .andReturn().getResponse().getContentAsString();
        long invoice = JSON.readTree(body).get("id").asLong();
        long line = JSON.readTree(body).get("lines").get(0).get("id").asLong();
        assertThat(jdbc.queryForObject("SELECT unit_cost FROM sales_document_lines WHERE document_id = ?",
                BigDecimal.class, invoice)).isEqualByComparingTo(cost);

        seller.post("/api/sales/" + invoice + "/lines", "{\"productId\":" + product + ",\"quantity\":\"1\"}")
                .andExpect(jsonPath("$.code").value("DOCUMENT_IMMUTABLE"));
        seller.perform(put("/api/sales/" + invoice + "/lines/" + line).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":" + product + ",\"quantity\":\"1\"}"), true)
                .andExpect(jsonPath("$.code").value("DOCUMENT_IMMUTABLE"));
        seller.perform(put("/api/sales/" + invoice).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":" + customer + ",\"creditSale\":true,\"version\":" + JSON.readTree(body).get("version").asLong() + "}"), true)
                .andExpect(jsonPath("$.errors[0].code").value("FROZEN"));

        confirm(invoice, "{}").andExpect(jsonPath("$.code").value("PAYMENT_TOTAL_MISMATCH"));
        confirm(invoice, cheque(TTC)).andExpect(status().isOk()).andExpect(jsonPath("$.number").value(startsWith("INV-")))
                .andExpect(jsonPath("$.payments[0].amount").value(TTC));
        assertThat(onHand()).isEqualByComparingTo("8");
        assertThat(movements(invoice)).isZero();
    }

    @Test
    void aCreditDeliveryNoteInvoiceInheritsCreditAndPassesNoNewCheck() throws Exception {
        limit(TTC);
        jdbc.update("UPDATE customers SET payment_terms_days = 15 WHERE id = ?", customer);
        entities.clear();
        long note = confirmedNote(true);
        limit("0.00");
        jdbc.update("UPDATE products SET is_archived = TRUE WHERE id = ?", product);
        entities.clear();
        long invoice = id(convert(note, "invoice").andExpect(jsonPath("$.creditSale").value(true)));
        seller.get("/api/customers/" + customer + "/balance").andExpect(jsonPath("$.balance").value("0.00"))
                .andExpect(jsonPath("$.uninvoicedDeliveryNotes").value(TTC));
        confirm(invoice, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDate").value(today.plusDays(15).toString()));
        seller.get("/api/customers/" + customer + "/balance").andExpect(jsonPath("$.balance").value(TTC))
                .andExpect(jsonPath("$.uninvoicedDeliveryNotes").value("0.00"));
    }

    @Test
    void oneDeliveryNoteGivesOneInvoiceUntilThatOneIsAbandoned() throws Exception {
        long note = confirmedNote(false);
        long invoice = id(convert(note, "invoice").andExpect(status().isCreated()));
        convert(note, "invoice").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        seller.perform(delete("/api/sales/" + invoice), true).andExpect(status().isNoContent());
        entities.clear();
        convert(note, "invoice").andExpect(status().isCreated());
    }

    @Test
    void aQuoteConvertsWithFefoLotsAtTheQuotedPrice() throws Exception {
        long second = fixtures.lot(product, "L2", today.plusYears(2), "10");
        jdbc.update("UPDATE lots SET quantity_on_hand = 1 WHERE id = ?", lot);
        long quote = draft("quote", false);
        jdbc.update("UPDATE products SET sale_price = 99 WHERE id = ?", product);
        entities.clear();
        convert(quote, "delivery_note").andExpect(status().isCreated())
                .andExpect(jsonPath("$.docType").value("delivery_note"))
                .andExpect(jsonPath("$.parentId").value(quote))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].lotId").value(lot))
                .andExpect(jsonPath("$.lines[0].quantity").value("1.000"))
                .andExpect(jsonPath("$.lines[1].lotId").value(second))
                .andExpect(jsonPath("$.lines[1].unitPrice").value("10.0000"))
                .andExpect(jsonPath("$.totals.ttc").value(TTC));
        long invoice = id(convert(quote, "invoice").andExpect(status().isCreated()));
        confirm(invoice, cheque(TTC)).andExpect(status().isOk());
        assertThat(movements(invoice)).isEqualTo(2);
    }

    @Test
    void aQuoteWithoutEnoughStockCannotConvert() throws Exception {
        long quote = draft("quote", false);
        jdbc.update("UPDATE lots SET quantity_on_hand = 1 WHERE id = ?", lot);
        entities.clear();
        convert(quote, "invoice").andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"));
    }

    @Test
    void onlyTheAllowedDerivationsAndStatusesConvert() throws Exception {
        long note = draft("delivery_note", false);
        convert(note, "invoice").andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        convert(note, "quote").andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        convert(note, "credit_note").andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        convert(note, "nonsense").andExpect(jsonPath("$.errors[0].code").value("UNKNOWN"));
        convert(note, null).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        long invoice = draft("invoice", false);
        convert(invoice, "credit_note").andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        long quote = draft("quote", false);
        seller.perform(delete("/api/sales/" + quote), true).andExpect(status().isNoContent());
        entities.clear();
        convert(quote, "invoice").andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        convert(999999999L, "invoice").andExpect(status().isNotFound());
    }

    @Test
    void anInactiveCustomerCannotConvert() throws Exception {
        long quote = draft("quote", false);
        jdbc.update("UPDATE customers SET is_active = FALSE WHERE id = ?", customer);
        entities.clear();
        convert(quote, "invoice").andExpect(jsonPath("$.code").value("CUSTOMER_INACTIVE"));
    }

    private long confirmedNote(boolean credit) throws Exception {
        long note = draft("delivery_note", credit);
        confirm(note, "{}").andExpect(status().isOk());
        entities.clear();
        return note;
    }

    private long draft(String type, boolean credit) throws Exception {
        long draft = id(seller.post("/api/sales", "{\"docType\":\"" + type + "\",\"creditSale\":" + credit
                + ",\"customerId\":" + customer + "}").andExpect(status().isCreated()));
        seller.post("/api/sales/" + draft + "/lines", "{\"productId\":" + product + ",\"quantity\":\"2\"}")
                .andExpect(status().isOk());
        return draft;
    }

    private ResultActions confirm(long id, String json) throws Exception {
        return seller.post("/api/sales/" + id + "/confirm", json);
    }

    private ResultActions convert(long id, String target) throws Exception {
        return seller.post("/api/sales/" + id + "/convert",
                target == null ? "{}" : "{\"targetType\":\"" + target + "\"}");
    }

    private void limit(String value) {
        jdbc.update("UPDATE customers SET credit_limit = ? WHERE id = ?", new BigDecimal(value), customer);
        entities.clear();
    }

    private BigDecimal onHand() {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", BigDecimal.class, lot);
    }

    private int movements(long document) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE reference_type = 'sales_document'"
                + " AND reference_id = ?", Integer.class, document);
    }

    private static long id(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private static String cheque(String amount) {
        return "{\"payments\":[{\"method\":\"cheque\",\"amount\":\"" + amount + "\",\"reference\":\"CHQ-1\"}]}";
    }
}
