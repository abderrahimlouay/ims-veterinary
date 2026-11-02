package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.Duration;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Customer returns through the API (W3, FR-SALE-006/007, ADR-127…131): a restock goes back into the original lot, a
 * discard enters and leaves it with its reason, the credit note is confirmed with its own number and the original's
 * customer and credit status; an immediate sale is refunded in full, a credit sale credited to the account; returned ≤
 * sold per line; what may be restocked; what may be returned and until when; idempotent; audited; the preview writes
 * nothing.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CustomerReturnsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** One unit at 10.00 HT + 9 % VAT. */
    private static final String UNIT_TTC = "10.90";

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

    private ApiClient admin;
    private ApiClient seller;
    private TestLots fixtures;
    private long amox;
    private long amoxLot;
    private long ivermec;
    private long ivermecLot;
    private long customer;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        LocalDate today = calendar.today();
        fixtures = new TestLots(jdbc);
        TestUser manager = new TestUsers(jdbc, encoder).create("Administrator");
        admin = ApiClient.create(context, "198.51.100.241");
        admin.login(manager.username(), manager.password()).andExpect(status().isOk());
        TestUser user = new TestUsers(jdbc, encoder).create("Seller");
        seller = ApiClient.create(context, "198.51.100.242");
        seller.login(user.username(), user.password()).andExpect(status().isOk());
        amox = fixtures.product("RET-AMX", "Amoxival retour");
        ivermec = fixtures.product("RET-IVM", "Ivermec retour");
        jdbc.update("UPDATE products SET sale_price = 10 WHERE id IN (?, ?)", amox, ivermec);
        amoxLot = fixtures.lot(amox, "RA-1", today.plusYears(1), "10");
        ivermecLot = fixtures.lot(ivermec, "RI-1", today.plusYears(1), "10");
        customer = id(admin.post("/api/customers", "{\"name\":\"Ferme Retour " + System.nanoTime() + "\"}")
                .andExpect(status().isCreated()));
        jdbc.update("UPDATE customers SET credit_limit = 1000 WHERE id = ?", customer);
        entities.clear();
    }

    @Test
    void aCashReceiptRestocksOneLineDiscardsTheOtherAndIsRefundedInCash() throws Exception {
        open("100");
        long receipt = confirmed("receipt", false, "{\"payments\":" + cash("43.60") + "}");
        long[] lines = saleLines(receipt);

        JsonNode created = body(create(receipt, "[" + restock(lines[0], "1") + "," + discard(lines[1], "1", "opened",
                null) + "]", cash("21.80")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(org.hamcrest.Matchers.startsWith("CR-")))
                .andExpect(jsonPath("$.document.id").value(receipt))
                .andExpect(jsonPath("$.creditNote.number").value(org.hamcrest.Matchers.startsWith("CN-")))
                .andExpect(jsonPath("$.creditNote.totalTtc").value("21.80"))
                .andExpect(jsonPath("$.lines[0].disposition").value("restock"))
                .andExpect(jsonPath("$.lines[0].movementIds.length()").value(1))
                .andExpect(jsonPath("$.lines[1].disposition").value("discard"))
                .andExpect(jsonPath("$.lines[1].discardReason").value("opened"))
                .andExpect(jsonPath("$.lines[1].movementIds.length()").value(2))
                .andExpect(jsonPath("$.refunds[0].method").value("cash"))
                .andExpect(jsonPath("$.refunds[0].amount").value("21.80")));
        entities.clear();

        assertThat(onHand(amoxLot)).isEqualByComparingTo("9");
        assertThat(onHand(ivermecLot)).isEqualByComparingTo("8");
        long returnLine = created.get("lines").get(1).get("id").asLong();
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(movement_type, ' ', CAST(quantity AS CHAR), ' ', COALESCE(reason, '-'))
                FROM stock_movements WHERE reference_type = 'customer_return_line' AND reference_id = ? ORDER BY id""",
                String.class, returnLine)).containsExactly("customer_return 1.000 -", "return_discard -1.000 opened");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(amoxLot, ivermecLot));
        assertThat(returned(lines[0])).isEqualByComparingTo("1");
        assertThat(returned(lines[1])).isEqualByComparingTo("1");
        long note = created.get("creditNote").get("id").asLong();
        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(doc_type, ' ', status, ' ', parent_id, ' ', is_credit_sale, ' ', COALESCE(customer_id, 0),
                              ' ', CAST(total_ht AS CHAR), ' ', CAST(total_vat AS CHAR))
                FROM sales_documents WHERE id = ?""", String.class, note))
                .isEqualTo("credit_note confirmed " + receipt + " 0 0 20.00 1.80");
        admin.get("/api/cash-sessions/current").andExpect(jsonPath("$.expectedAmount").value("121.80"));
        assertThat(jdbc.queryForObject("SELECT JSON_LENGTH(new_values, '$.lines') FROM audit_log"
                + " WHERE action = 'RETURN_CREATE' AND entity = 'customer_returns' AND entity_id = ?", Integer.class,
                created.get("id").asLong())).isEqualTo(2);
        admin.get("/api/returns/customer/" + created.get("id").asLong()).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[1].lot.status").value("active"))
                .andExpect(jsonPath("$.createdBy").isNumber());
    }

    @Test
    void aCreditInvoiceIsCreditedToTheAccountWithoutRefund() throws Exception {
        long invoice = confirmed("invoice", true, "{}");
        long[] lines = saleLines(invoice);
        admin.get("/api/customers/" + customer + "/balance").andExpect(jsonPath("$.balance").value("43.60"));

        create(invoice, "[" + restock(lines[0], "2") + "]", cash(UNIT_TTC)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REFUND_TOTAL_MISMATCH"));
        create(invoice, "[" + restock(lines[0], "2") + "]", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.refunds").isEmpty())
                .andExpect(jsonPath("$.customer.id").value(customer));
        entities.clear();

        admin.get("/api/customers/" + customer + "/balance").andExpect(jsonPath("$.balance").value("21.80"));
        assertThat(onHand(amoxLot)).isEqualByComparingTo("10");
    }

    @Test
    void theReturnedQuantityNeverExceedsTheSoldOneAcrossReturns() throws Exception {
        long invoice = confirmed("invoice", true, "{}");
        long line = saleLines(invoice)[0];

        create(invoice, "[" + restock(line, "3") + "]", null).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RETURN_QUANTITY_EXCEEDED"))
                .andExpect(jsonPath("$.context.returnableBase").value("2.000"));
        create(invoice, "[" + restock(line, "1") + "]", null).andExpect(status().isCreated());
        entities.clear();
        create(invoice, "[" + restock(line, "1.5") + "]", null)
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        create(invoice, "[" + restock(line, "2") + "]", null).andExpect(jsonPath("$.code").value("RETURN_QUANTITY_EXCEEDED"))
                .andExpect(jsonPath("$.context.returnableBase").value("1.000"));
        create(invoice, "[" + restock(line, "1") + "]", null).andExpect(status().isCreated());
        entities.clear();

        assertThat(returned(line)).isEqualByComparingTo("2");
        create(invoice, "[" + restock(line, "1") + "]", null).andExpect(jsonPath("$.code").value("RETURN_QUANTITY_EXCEEDED"));
        assertThat(jdbc.queryForObject("SELECT SUM(total_ttc) FROM sales_documents WHERE parent_id = ?"
                + " AND doc_type = 'credit_note'", BigDecimal.class, invoice)).isEqualByComparingTo("21.80");
    }

    @Test
    void theLastReturnOfALineCreditsTheExactRemainder() throws Exception {
        jdbc.update("UPDATE products SET sale_price = 0.335, vat_rate = 19 WHERE id = ?", amox);
        entities.clear();
        long invoice = confirmedOf("invoice", true, "{}", "3", null);
        long line = saleLines(invoice)[0];
        assertThat(jdbc.queryForObject("SELECT CAST(line_ttc AS CHAR) FROM sales_document_lines WHERE id = ?",
                String.class, line)).isEqualTo("1.20");

        for (int i = 0; i < 3; i++) {
            create(invoice, "[" + restock(line, "1") + "]", null).andExpect(status().isCreated());
            entities.clear();
        }

        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(CAST(SUM(l.line_ht) AS CHAR), ' ', CAST(SUM(l.line_vat) AS CHAR))
                FROM sales_document_lines l JOIN sales_documents d ON d.id = l.document_id
                WHERE d.parent_id = ? AND d.doc_type = 'credit_note'""", String.class, invoice)).isEqualTo("1.01 0.19");
    }

    @Test
    void whatMayGoBackIntoItsLotAndWhatMustBeDiscarded() throws Exception {
        long invoice = confirmed("invoice", true, "{}");
        long[] lines = saleLines(invoice);

        create(invoice, "[" + line(lines[0], "1", false, "restock", null, null) + "]", null)
                .andExpect(jsonPath("$.code").value("RETURN_NOT_RESTOCKABLE"))
                .andExpect(jsonPath("$.context.reason").value("not_sealed"))
                .andExpect(jsonPath("$.context.saleLineId").value(lines[0]));
        jdbc.update("UPDATE lots SET expiry_date = ? WHERE id = ?", calendar.today().minusDays(1), amoxLot);
        jdbc.update("UPDATE products SET return_restock_forbidden = TRUE WHERE id = ?", ivermec);
        entities.clear();
        create(invoice, "[" + restock(lines[0], "1") + "]", null).andExpect(jsonPath("$.context.reason").value("lot_expired"));
        create(invoice, "[" + restock(lines[1], "1") + "]", null)
                .andExpect(jsonPath("$.context.reason").value("product_restricted"));
        jdbc.update("UPDATE lots SET status = 'blocked' WHERE id = ?", ivermecLot);
        entities.clear();
        create(invoice, "[" + restock(lines[1], "1") + "]", null)
                .andExpect(jsonPath("$.context.reason").value("product_restricted"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_returns WHERE document_id = ?", Integer.class,
                invoice)).isZero();

        jdbc.update("UPDATE lots SET status = 'recalled' WHERE id = ?", ivermecLot);
        entities.clear();
        create(invoice, "[" + restock(lines[1], "1") + "," + discard(lines[0], "1", "expired", null) + "]", null)
                .andExpect(status().isCreated());
        entities.clear();
        assertThat(onHand(ivermecLot)).isEqualByComparingTo("9");
        assertThat(jdbc.queryForObject("SELECT status FROM lots WHERE id = ?", String.class, ivermecLot))
                .isEqualTo("recalled");
        assertThat(onHand(amoxLot)).isEqualByComparingTo("8");
    }

    @Test
    void sealedGoodsGoBackIntoABlockedLotWhichStaysBlocked() throws Exception {
        long invoice = confirmed("invoice", true, "{}");
        long line = saleLines(invoice)[0];
        jdbc.update("UPDATE lots SET status = 'blocked' WHERE id = ?", amoxLot);
        entities.clear();

        create(invoice, "[" + restock(line, "2") + "]", null).andExpect(status().isCreated());
        entities.clear();

        assertThat(onHand(amoxLot)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT status FROM lots WHERE id = ?", String.class, amoxLot))
                .isEqualTo("blocked");
    }

    @Test
    void onlyAConfirmedInvoiceOrReceiptIsReturned() throws Exception {
        long note = confirmed("delivery_note", true, "{}");
        long noteLine = saleLines(note)[0];
        create(note, "[" + restock(noteLine, "1") + "]", null)
                .andExpect(jsonPath("$.code").value("RETURN_REQUIRES_INVOICE"));
        long invoice = id(seller.post("/api/sales/" + note + "/convert", "{\"targetType\":\"invoice\"}")
                .andExpect(status().isCreated()));
        admin.post("/api/sales/" + invoice + "/confirm", "{}").andExpect(status().isOk());
        entities.clear();
        create(invoice, "[" + restock(saleLines(invoice)[0], "1") + "]", null).andExpect(status().isCreated());
        entities.clear();
        assertThat(onHand(amoxLot)).isEqualByComparingTo("9");

        long quote = confirmed("quote", false, "{}");
        create(quote, "[" + restock(saleLines(quote)[0], "1") + "]", null)
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        long draft = draft("invoice", true, "1", null);
        create(draft, "[" + restock(saleLines(draft)[0], "1") + "]", null)
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        create(999_999_999L, "[" + restock(1, "1") + "]", null).andExpect(status().isNotFound());
    }

    @Test
    void theAgeLimitIsOffByDefaultAndCountsBusinessDaysWhenSet() throws Exception {
        long invoice = confirmed("invoice", true, "{}");
        long line = saleLines(invoice)[0];
        issuedDaysAgo(invoice, 400);
        create(invoice, "[" + restock(line, "1") + "]", null).andExpect(status().isCreated());
        entities.clear();

        long recent = confirmed("invoice", true, "{}");
        long recentLine = saleLines(recent)[0];
        jdbc.update("UPDATE app_settings SET setting_value = '30' WHERE setting_key = 'customer_return_max_days'");
        issuedDaysAgo(recent, 30);
        create(recent, "[" + restock(recentLine, "1") + "]", null).andExpect(status().isCreated());
        entities.clear();
        issuedDaysAgo(recent, 31);
        create(recent, "[" + restock(recentLine, "1") + "]", null)
                .andExpect(jsonPath("$.code").value("RETURN_PERIOD_EXPIRED"))
                .andExpect(jsonPath("$.context.maxDays").value(30));
    }

    @Test
    void aRepeatedKeyReturnsTheFirstReturnAndAnotherBodyConflicts() throws Exception {
        long invoice = confirmed("invoice", true, "{}");
        long line = saleLines(invoice)[0];
        String key = UUID.randomUUID().toString();
        String body = request(invoice, "[" + restock(line, "1") + "]", null, "Erreur de produit");

        long first = id(post(body, key).andExpect(status().isCreated()));
        entities.clear();
        assertThat(id(post(body, key))).isEqualTo(first);
        post(request(invoice, "[" + restock(line, "1") + "]", null, "Autre"), key)
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertThat(returned(line)).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_returns WHERE document_id = ?", Integer.class,
                invoice)).isOne();
    }

    @Test
    void invalidRequestsAreFieldErrors() throws Exception {
        long invoice = confirmed("invoice", true, "{}");
        long[] lines = saleLines(invoice);
        long other = saleLines(confirmed("invoice", true, "{}"))[0];

        assertField(request(invoice, "[" + restock(lines[0], "1") + "]", null, " "), "reason", "REQUIRED");
        assertField(request(invoice, "[]", null, "R"), "lines", "REQUIRED");
        assertField(request(invoice, "[" + restock(other, "1") + "]", null, "R"), "lines[0].saleLineId",
                "OTHER_DOCUMENT");
        assertField(request(invoice, "[" + restock(lines[0], "1") + "," + restock(lines[0], "1") + "]", null, "R"),
                "lines[1].saleLineId", "DUPLICATE");
        assertField(request(invoice, "[" + restock(lines[0], "0") + "]", null, "R"), "lines[0].quantity", "POSITIVE");
        assertField(request(invoice, "[" + line(lines[0], "1", null, "restock", null, null) + "]", null, "R"),
                "lines[0].sealed", "REQUIRED");
        assertField(request(invoice, "[" + line(lines[0], "1", true, "keep", null, null) + "]", null, "R"),
                "lines[0].disposition", "UNKNOWN");
        assertField(request(invoice, "[" + discard(lines[0], "1", null, null) + "]", null, "R"),
                "lines[0].discardReason", "REQUIRED");
        assertField(request(invoice, "[" + discard(lines[0], "1", "melted", null) + "]", null, "R"),
                "lines[0].discardReason", "UNKNOWN");
        assertField(request(invoice, "[" + discard(lines[0], "1", "other", " ") + "]", null, "R"),
                "lines[0].discardNote", "REQUIRED");
        assertField(request(invoice, "[" + line(lines[0], "1", true, "restock", "opened", null) + "]", null, "R"),
                "lines[0].discardReason", "NOT_ALLOWED");
        assertField(request(invoice, "[" + restock(lines[0], "1").replace("}", ",\"unit\":\"box\"}") + "]", null,
                "R"), "lines[0].unit", "UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_returns WHERE document_id = ?", Integer.class,
                invoice)).isZero();
    }

    @Test
    void aSellerCannotReturnAndThePreviewWritesNothing() throws Exception {
        long invoice = confirmed("invoice", true, "{}");
        long[] lines = saleLines(invoice);
        seller.post("/api/returns/customer", request(invoice, "[" + restock(lines[0], "1") + "]", null, "R"))
                .andExpect(status().isForbidden());

        admin.post("/api/returns/customer/preview", "{\"documentId\":" + invoice + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].returnableBase").value("2.000"))
                .andExpect(jsonPath("$.lines[0].restockAllowed").value(true))
                .andExpect(jsonPath("$.refundRequired").value(false))
                .andExpect(jsonPath("$.creditNote.totalTtc").value("0.00"));
        admin.post("/api/returns/customer/preview", "{\"documentId\":" + invoice + ",\"lines\":["
                + line(lines[0], "1", false, "discard", "opened", null) + "]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].restockAllowed").value(false))
                .andExpect(jsonPath("$.lines[0].restockRefusal").value("not_sealed"))
                .andExpect(jsonPath("$.creditNote.totalTtc").value(UNIT_TTC));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_documents WHERE parent_id = ?", Integer.class,
                invoice)).isZero();
        assertThat(returned(lines[0])).isEqualByComparingTo("0");
    }

    @Test
    void aCashRefundBeyondTheDrawerIsRefusedAndNothingIsWritten() throws Exception {
        long receipt = confirmed("receipt", false, cheque("43.60"));
        open("5");
        long line = saleLines(receipt)[0];

        create(receipt, "[" + restock(line, "1") + "]", cash(UNIT_TTC))
                .andExpect(jsonPath("$.code").value("CASH_INSUFFICIENT"));
        create(receipt, "[" + restock(line, "1") + "]", "[{\"method\":\"cheque\",\"amount\":\"10.90\","
                + "\"reference\":\"CHQ-R\"}]").andExpect(status().isCreated())
                .andExpect(jsonPath("$.refunds[0].method").value("cheque"));
    }

    /** The original issued {@code days} business days before today, at the same time of day. */
    private void issuedDaysAgo(long document, int days) {
        jdbc.update("UPDATE sales_documents SET issued_at = ? WHERE id = ?",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(days))), document);
        entities.clear();
    }

    private void open(String amount) throws Exception {
        admin.post("/api/cash-sessions/open", "{\"openingAmount\":\"" + amount + "\"}").andExpect(status().isCreated());
    }

    /** Two lines, 2 Amoxival and 2 Ivermec at 10.00 + 9 %: 43.60. */
    private long confirmed(String type, boolean credit, String payments) throws Exception {
        return confirmedOf(type, credit, payments, "2", "2");
    }

    private long confirmedOf(String type, boolean credit, String payments, String amoxQty, String ivermecQty)
            throws Exception {
        long draft = draft(type, credit, amoxQty, ivermecQty);
        admin.post("/api/sales/" + draft + "/confirm", payments).andExpect(status().isOk());
        entities.clear();
        return draft;
    }

    private long draft(String type, boolean credit, String amoxQty, String ivermecQty) throws Exception {
        String party = "receipt".equals(type) ? "" : ",\"customerId\":" + customer;
        long draft = id(seller.post("/api/sales", "{\"docType\":\"" + type + "\",\"creditSale\":" + credit + party + "}")
                .andExpect(status().isCreated()));
        seller.post("/api/sales/" + draft + "/lines", "{\"productId\":" + amox + ",\"quantity\":\"" + amoxQty + "\"}")
                .andExpect(status().isOk());
        if (ivermecQty != null) {
            seller.post("/api/sales/" + draft + "/lines", "{\"productId\":" + ivermec + ",\"quantity\":\"" + ivermecQty
                    + "\"}").andExpect(status().isOk());
        }
        return draft;
    }

    private long[] saleLines(long document) {
        return jdbc.queryForList("SELECT id FROM sales_document_lines WHERE document_id = ? ORDER BY id", Long.class,
                document).stream().mapToLong(Long::longValue).toArray();
    }

    private ResultActions create(long document, String lines, String refund) throws Exception {
        return post(request(document, lines, refund, "Erreur de produit"), null);
    }

    private ResultActions post(String body, String key) throws Exception {
        var request = MockMvcRequestBuilders.post("/api/returns/customer").contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return admin.perform(request, true);
    }

    private void assertField(String body, String field, String code) throws Exception {
        post(body, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code));
    }

    private static String request(long document, String lines, String refund, String reason) {
        return "{\"documentId\":" + document + ",\"reason\":\"" + reason + "\",\"lines\":" + lines
                + (refund == null ? "" : ",\"refund\":" + refund) + "}";
    }

    private static String restock(long saleLine, String quantity) {
        return line(saleLine, quantity, true, "restock", null, null);
    }

    private static String discard(long saleLine, String quantity, String reason, String note) {
        return line(saleLine, quantity, false, "discard", reason, note);
    }

    private static String line(long saleLine, String quantity, Boolean sealed, String disposition, String reason,
            String note) {
        return "{\"saleLineId\":" + saleLine + ",\"quantity\":\"" + quantity + "\""
                + (sealed == null ? "" : ",\"sealed\":" + sealed) + ",\"disposition\":\"" + disposition + "\""
                + (reason == null ? "" : ",\"discardReason\":\"" + reason + "\"")
                + (note == null ? "" : ",\"discardNote\":\"" + note + "\"") + "}";
    }

    private BigDecimal onHand(long lot) {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", BigDecimal.class, lot);
    }

    private BigDecimal returned(long saleLine) {
        return jdbc.queryForObject("SELECT returned_quantity_base FROM sales_document_lines WHERE id = ?",
                BigDecimal.class, saleLine);
    }

    private static JsonNode body(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static long id(ResultActions result) throws Exception {
        return body(result).get("id").asLong();
    }

    private static String cash(String amount) {
        return "[{\"method\":\"cash\",\"amount\":\"" + amount + "\"}]";
    }

    private static String cheque(String amount) {
        return "{\"payments\":[{\"method\":\"cheque\",\"amount\":\"" + amount + "\",\"reference\":\"CHQ-1\"}]}";
    }
}
