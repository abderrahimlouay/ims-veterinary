package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cancelling a confirmed document (W2b, BR-SALE-014, ADR-119, ADR-125): whole, with a reason and {@code sale.cancel};
 * refused with a later document or a closed session; stock back to its lot, payments reversed, number kept, audited.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SaleCancellationTest {

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

    private ApiClient admin;
    private ApiClient seller;
    private long product;
    private long lot;
    private long customer;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        LocalDate today = calendar.today();
        TestLots fixtures = new TestLots(jdbc);
        TestUser manager = new TestUsers(jdbc, encoder).create("Administrator");
        admin = ApiClient.create(context, "198.51.100.231");
        admin.login(manager.username(), manager.password()).andExpect(status().isOk());
        TestUser user = new TestUsers(jdbc, encoder).create("Seller");
        seller = ApiClient.create(context, "198.51.100.232");
        seller.login(user.username(), user.password()).andExpect(status().isOk());
        product = fixtures.product("ANNUL-1", "Annulé");
        jdbc.update("UPDATE products SET sale_price = 10 WHERE id = ?", product);
        lot = fixtures.lot(product, "L1", today.plusYears(1), "10");
        customer = id(admin.post("/api/customers", "{\"name\":\"Ferme Annulation " + System.nanoTime() + "\"}")
                .andExpect(status().isCreated()));
        jdbc.update("UPDATE customers SET credit_limit = 100 WHERE id = ?", customer);
        entities.clear();
    }

    @Test
    void aCashReceiptReturnsItsStockReversesItsCashKeepsItsNumberAndIsAudited() throws Exception {
        admin.post("/api/cash-sessions/open", "{\"openingAmount\":\"100\"}").andExpect(status().isCreated());
        long receipt = draft("receipt", false);
        String number = JSON.readTree(confirm(receipt, cash(TTC)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("number").asString();
        entities.clear();

        cancel(receipt, " erreur de caisse ").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.number").value(number))
                .andExpect(jsonPath("$.cancelReason").value("erreur de caisse"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty())
                .andExpect(jsonPath("$.payments").isEmpty());
        assertThat(onHand()).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT quantity FROM stock_movements WHERE reference_type = 'sales_document'"
                + " AND reference_id = ? AND movement_type = 'sale_cancellation'", BigDecimal.class, receipt))
                .isEqualByComparingTo("2");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
        admin.get("/api/cash-sessions/current").andExpect(jsonPath("$.expectedAmount").value("100.00"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'INVOICE_CANCEL' AND entity_id = ?",
                Integer.class, receipt)).isOne();
    }

    @Test
    void aDepletedLotIsSellableAgainAndABlockedOneStaysBlocked() throws Exception {
        jdbc.update("UPDATE lots SET quantity_on_hand = 2 WHERE id = ?", lot);
        entities.clear();
        long receipt = draft("receipt", false);
        confirm(receipt, cheque(TTC)).andExpect(status().isOk());
        entities.clear();
        assertThat(onHand()).isEqualByComparingTo("0");
        cancel(receipt, "erreur").andExpect(status().isOk());
        entities.clear();

        long second = draft("receipt", false);
        confirm(second, cheque(TTC)).andExpect(status().isOk());
        jdbc.update("UPDATE lots SET status = 'blocked' WHERE id = ?", lot);
        entities.clear();
        cancel(second, "erreur").andExpect(status().isOk());
        assertThat(lotStatus()).isEqualTo("blocked");
    }

    @Test
    void aCancelledCreditInvoiceLeavesTheBalance() throws Exception {
        long invoice = draft("invoice", true);
        confirm(invoice, "{}").andExpect(status().isOk());
        entities.clear();
        admin.get("/api/customers/" + customer + "/balance").andExpect(jsonPath("$.balance").value(TTC));
        cancel(invoice, "erreur").andExpect(status().isOk());
        admin.get("/api/customers/" + customer + "/balance").andExpect(jsonPath("$.balance").value("0.00"));
        assertThat(onHand()).isEqualByComparingTo("10");
    }

    @Test
    void aDeliveryNoteInvoicePostsNothingAndFreesTheNote() throws Exception {
        long note = draft("delivery_note", true);
        confirm(note, "{}").andExpect(status().isOk());
        entities.clear();
        long invoice = id(convert(note, "invoice").andExpect(status().isCreated()));
        cancel(note, "erreur").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SALE_NOT_CANCELLABLE"))
                .andExpect(jsonPath("$.context.blockedBy").value("derived_document"));
        confirm(invoice, "{}").andExpect(status().isOk());
        entities.clear();
        cancel(note, "erreur").andExpect(jsonPath("$.code").value("SALE_NOT_CANCELLABLE"));

        cancel(invoice, "erreur de facture").andExpect(status().isOk());
        assertThat(onHand()).isEqualByComparingTo("8");
        assertThat(cancellations(invoice)).isZero();
        admin.get("/api/customers/" + customer + "/balance").andExpect(jsonPath("$.balance").value("0.00"))
                .andExpect(jsonPath("$.uninvoicedDeliveryNotes").value(TTC));

        long again = id(convert(note, "invoice").andExpect(status().isCreated()));
        seller.perform(delete("/api/sales/" + again), true).andExpect(status().isNoContent());
        entities.clear();
        cancel(note, "erreur").andExpect(status().isOk());
        assertThat(onHand()).isEqualByComparingTo("10");
        admin.get("/api/customers/" + customer + "/balance").andExpect(jsonPath("$.uninvoicedDeliveryNotes").value("0.00"));
    }

    @Test
    void aQuoteIsCancelledWithoutMovementUnlessItHasADerivedDocument() throws Exception {
        long quote = draft("quote", false);
        confirm(quote, "{}").andExpect(status().isOk());
        entities.clear();
        long derived = id(convert(quote, "invoice").andExpect(status().isCreated()));
        cancel(quote, "erreur").andExpect(jsonPath("$.code").value("SALE_NOT_CANCELLABLE"));
        seller.perform(delete("/api/sales/" + derived), true).andExpect(status().isNoContent());
        entities.clear();
        cancel(quote, "erreur").andExpect(status().isOk());
        assertThat(cancellations(quote)).isZero();
    }

    @Test
    void aClosedSessionRefusesAndAChequeSaleHasNoSession() throws Exception {
        admin.post("/api/cash-sessions/open", "{\"openingAmount\":\"0\"}").andExpect(status().isCreated());
        long receipt = draft("receipt", false);
        confirm(receipt, cash(TTC)).andExpect(status().isOk());
        jdbc.update("UPDATE cash_sessions SET status = 'closed' WHERE id = "
                + "(SELECT cash_session_id FROM sales_documents WHERE id = ?)", receipt);
        entities.clear();
        cancel(receipt, "erreur").andExpect(jsonPath("$.code").value("SALE_NOT_CANCELLABLE"))
                .andExpect(jsonPath("$.context.blockedBy").value("cash_session"));
        assertThat(onHand()).isEqualByComparingTo("8");
        assertThat(jdbc.queryForObject("SELECT status FROM sales_documents WHERE id = ?", String.class, receipt))
                .isEqualTo("confirmed");

        long byCheque = draft("receipt", false);
        confirm(byCheque, cheque(TTC)).andExpect(status().isOk());
        entities.clear();
        cancel(byCheque, "erreur").andExpect(status().isOk()).andExpect(jsonPath("$.payments").isEmpty());
    }

    @Test
    void itNeedsAReasonAConfirmedDocumentAndThePermission() throws Exception {
        long draft = draft("receipt", false);
        cancel(draft, "erreur").andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        confirm(draft, cheque(TTC)).andExpect(status().isOk());
        entities.clear();
        cancel(draft, "  ").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        cancel(draft, "x".repeat(501)).andExpect(jsonPath("$.errors[0].code").value("SIZE"));
        seller.post("/api/sales/" + draft + "/cancel", "{\"reason\":\"erreur\"}").andExpect(status().isForbidden());
        cancel(draft, "erreur").andExpect(status().isOk());
        entities.clear();
        cancel(draft, "encore").andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        assertThat(cancellations(draft)).isOne();
        cancel(999999999L, "erreur").andExpect(status().isNotFound());
    }

    private long draft(String type, boolean credit) throws Exception {
        String party = "receipt".equals(type) ? "" : ",\"customerId\":" + customer;
        long draft = id(seller.post("/api/sales", "{\"docType\":\"" + type + "\",\"creditSale\":" + credit + party + "}")
                .andExpect(status().isCreated()));
        seller.post("/api/sales/" + draft + "/lines", "{\"productId\":" + product + ",\"quantity\":\"2\"}")
                .andExpect(status().isOk());
        return draft;
    }

    /** Through the administrator: the cash session under test is theirs. */
    private ResultActions confirm(long id, String json) throws Exception {
        return admin.post("/api/sales/" + id + "/confirm", json);
    }

    private ResultActions convert(long id, String target) throws Exception {
        return seller.post("/api/sales/" + id + "/convert", "{\"targetType\":\"" + target + "\"}");
    }

    private ResultActions cancel(long id, String reason) throws Exception {
        return admin.post("/api/sales/" + id + "/cancel", "{\"reason\":\"" + reason + "\"}");
    }

    private BigDecimal onHand() {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", BigDecimal.class, lot);
    }

    private String lotStatus() {
        return jdbc.queryForObject("SELECT status FROM lots WHERE id = ?", String.class, lot);
    }

    private int cancellations(long document) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE reference_type = 'sales_document'"
                + " AND reference_id = ? AND movement_type = 'sale_cancellation'", Integer.class, document);
    }

    private static long id(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private static String cash(String amount) {
        return "{\"payments\":[{\"method\":\"cash\",\"amount\":\"" + amount + "\"}]}";
    }

    private static String cheque(String amount) {
        return "{\"payments\":[{\"method\":\"cheque\",\"amount\":\"" + amount + "\",\"reference\":\"CHQ-1\"}]}";
    }
}
