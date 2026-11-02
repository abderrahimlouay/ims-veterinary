package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers.TestUser;
import com.imsvet.ims.support.TestUsers;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
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
 * What a customer return changes elsewhere (ADR-131): its document can no longer be cancelled, the refusal naming
 * the return (BR-RET-008); the recall buyers list shows per document the sold, returned and net quantity, a delivery
 * note's invoice counting on the note (BR-LOT-008); the sale's detail shows what was returned of each line and its
 * returns with their credit notes.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ReturnEffectsTest {

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
    void aReturnedDocumentCannotBeCancelledAndTheRefusalNamesTheReturn() throws Exception {
        long invoice = confirmed("invoice", true, "{}");
        long returned = id(create(invoice, "[" + restock(saleLines(invoice)[0], "1") + "]", null)
                .andExpect(status().isCreated()));
        entities.clear();

        admin.post("/api/sales/" + invoice + "/cancel", "{\"reason\":\"erreur\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SALE_NOT_CANCELLABLE"))
                .andExpect(jsonPath("$.context.blockedBy").value("return"))
                .andExpect(jsonPath("$.context.returnId").value(returned));
        assertThat(jdbc.queryForObject("SELECT status FROM sales_documents WHERE id = ?", String.class, invoice))
                .isEqualTo("confirmed");
    }

    @Test
    void theRecallListShowsWhatEachBuyerStillHoldsAfterReturns() throws Exception {
        long receipt = confirmed("receipt", false, cheque("43.60"));
        long note = confirmed("delivery_note", true, "{}");
        long invoice = id(seller.post("/api/sales/" + note + "/convert", "{\"targetType\":\"invoice\"}")
                .andExpect(status().isCreated()));
        admin.post("/api/sales/" + invoice + "/confirm", "{}").andExpect(status().isOk());
        entities.clear();
        create(receipt, "[" + discard(saleLines(receipt)[0], "1", "opened", null) + "]",
                "[{\"method\":\"cheque\",\"amount\":\"10.90\",\"reference\":\"CHQ-R\"}]")
                .andExpect(status().isCreated());
        entities.clear();
        create(invoice, "[" + restock(saleLines(invoice)[0], "2") + "]", null).andExpect(status().isCreated());
        entities.clear();

        admin.post("/api/lots/" + amoxLot + "/recall", "{\"reason\":\"Avis du fabricant\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.buyers.length()").value(1))
                .andExpect(jsonPath("$.buyers[0].documentType").value("delivery_note"))
                .andExpect(jsonPath("$.buyers[0].quantityBase").value("2.000"))
                .andExpect(jsonPath("$.buyers[0].returnedBase").value("2.000"))
                .andExpect(jsonPath("$.buyers[0].netBase").value("0.000"))
                .andExpect(jsonPath("$.anonymousSales[0].quantityBase").value("2.000"))
                .andExpect(jsonPath("$.anonymousSales[0].returnedBase").value("1.000"))
                .andExpect(jsonPath("$.anonymousSales[0].netBase").value("1.000"));
    }

    @Test
    void theSaleDetailShowsTheReturnedQuantityAndTheReturns() throws Exception {
        long invoice = confirmed("invoice", true, "{}");
        long[] lines = saleLines(invoice);
        long returned = id(create(invoice, "[" + restock(lines[0], "1") + "]", null).andExpect(status().isCreated()));
        entities.clear();

        admin.get("/api/sales/" + invoice).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].returnedQuantityBase").value("1.000"))
                .andExpect(jsonPath("$.lines[1].returnedQuantityBase").value("0.000"))
                .andExpect(jsonPath("$.returns.length()").value(1))
                .andExpect(jsonPath("$.returns[0].id").value(returned))
                .andExpect(jsonPath("$.returns[0].number").value(org.hamcrest.Matchers.startsWith("CR-")))
                .andExpect(jsonPath("$.returns[0].creditNoteNumber").value(org.hamcrest.Matchers.startsWith("CN-")))
                .andExpect(jsonPath("$.returns[0].creditNoteTtc").value("10.90"));
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
