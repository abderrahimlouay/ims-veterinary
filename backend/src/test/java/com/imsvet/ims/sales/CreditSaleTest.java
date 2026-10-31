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
import java.time.LocalDate;
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
 * Credit sales (W2 step 6): the limit (BR-PAY-005, ADR-108: null = none, 0 = cash only, overdue only with its setting),
 * uninvoiced credit delivery notes in the check but not the balance (ADR-117), the balance (BR-PAY-004, ADR-118), the due
 * date from the payment terms, no payment at confirmation, and the two customer endpoints.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CreditSaleTest {

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

    private ApiClient seller;
    private ApiClient manager;
    private long operator;
    private long product;
    private long lot;
    private long customer;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        today = calendar.today();
        TestLots fixtures = new TestLots(jdbc);
        operator = fixtures.fixtureUser();
        TestUser user = new TestUsers(jdbc, encoder).create("Seller");
        seller = ApiClient.create(context, "198.51.100.211");
        seller.login(user.username(), user.password()).andExpect(status().isOk());
        TestUser admin = new TestUsers(jdbc, encoder).create("Administrator");
        manager = ApiClient.create(context, "198.51.100.212");
        manager.login(admin.username(), admin.password()).andExpect(status().isOk());
        product = fixtures.product("CRED-1", "À crédit");
        jdbc.update("UPDATE products SET sale_price = 10 WHERE id = ?", product);
        lot = fixtures.lot(product, "L1", today.plusYears(1), "100");
        customer = JSON.readTree(seller.post("/api/customers", "{\"name\":\"Ferme Crédit " + System.nanoTime() + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        limit("100.00");
    }

    @Test
    void aCreditSaleWithinTheLimitMovesStockTakesNoPaymentAndRaisesTheBalance() throws Exception {
        confirm(creditDraft()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"))
                .andExpect(jsonPath("$.payments").isEmpty())
                .andExpect(jsonPath("$.cashSessionId").isEmpty())
                .andExpect(jsonPath("$.customerBalance").value(TTC));
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", BigDecimal.class, lot))
                .isEqualByComparingTo("98");
        seller.get("/api/customers/" + customer + "/balance").andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(TTC))
                .andExpect(jsonPath("$.uninvoicedDeliveryNotes").value("0.00"))
                .andExpect(jsonPath("$.creditLimit").value("100.00"))
                .andExpect(jsonPath("$.availableCredit").value("78.20"));
    }

    @Test
    void theLimitItselfIsAllowedAndOneCentMoreIsRefusedLeavingNothing() throws Exception {
        limit(TTC);
        confirm(creditDraft()).andExpect(status().isOk());
        limit("43.59");
        long second = creditDraft();
        confirm(second).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CREDIT_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.context.limit").value("43.59"))
                .andExpect(jsonPath("$.context.balance").value(TTC))
                .andExpect(jsonPath("$.context.total").value(TTC));
        assertThat(jdbc.queryForObject("SELECT status FROM sales_documents WHERE id = ?", String.class, second))
                .isEqualTo("draft");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE reference_type = 'sales_document'"
                + " AND reference_id = ?", Integer.class, second)).isZero();
        limit("43.60");
        entities.clear();
        confirm(second).andExpect(status().isOk());
    }

    @Test
    void noLimitAllowsAnyAmountAndZeroIsCashOnly() throws Exception {
        limit(null);
        confirm(creditDraft()).andExpect(status().isOk());
        seller.get("/api/customers/" + customer + "/balance").andExpect(jsonPath("$.creditLimit").isEmpty())
                .andExpect(jsonPath("$.availableCredit").isEmpty());
        limit("0.00");
        confirm(creditDraft()).andExpect(jsonPath("$.code").value("CREDIT_LIMIT_EXCEEDED"));
        long free = creditDraft();
        jdbc.update("UPDATE sales_document_lines SET unit_price = 0 WHERE document_id = ?", free);
        entities.clear();
        confirm(free).andExpect(jsonPath("$.code").value("CREDIT_LIMIT_EXCEEDED"));
    }

    @Test
    void aCreditSaleWithAPaymentIsRefused() throws Exception {
        seller.post("/api/sales/" + creditDraft() + "/confirm", "{\"payments\":[{\"method\":\"cheque\",\"amount\":\""
                + TTC + "\",\"reference\":\"CHQ-1\"}]}")
                .andExpect(jsonPath("$.code").value("PAYMENT_TOTAL_MISMATCH"));
    }

    @Test
    void theDueDateDefaultsToThePaymentTermsAndAGivenOneIsKept() throws Exception {
        jdbc.update("UPDATE customers SET payment_terms_days = 30 WHERE id = ?", customer);
        entities.clear();
        confirm(creditDraft()).andExpect(jsonPath("$.dueDate").value(today.plusDays(30).toString()));
        long given = draft("\"dueDate\":\"" + today.plusDays(7) + "\"");
        confirm(given).andExpect(jsonPath("$.dueDate").value(today.plusDays(7).toString()));
    }

    @Test
    void uninvoicedCreditDeliveryNotesCountInTheCheckNotTheBalance() throws Exception {
        long note = document("delivery_note", "DN-1", true, "90.00", null, null);
        seller.get("/api/customers/" + customer + "/balance").andExpect(jsonPath("$.balance").value("0.00"))
                .andExpect(jsonPath("$.uninvoicedDeliveryNotes").value("90.00"))
                .andExpect(jsonPath("$.availableCredit").value("10.00"));
        long sale = creditDraft();
        confirm(sale).andExpect(jsonPath("$.code").value("CREDIT_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.context.uninvoicedDeliveryNotes").value("90.00"));

        long invoice = document("invoice", "INV-DN", true, "90.00", note, null);
        seller.get("/api/customers/" + customer + "/balance").andExpect(jsonPath("$.balance").value("90.00"))
                .andExpect(jsonPath("$.uninvoicedDeliveryNotes").value("0.00"));
        jdbc.update("UPDATE sales_documents SET status = 'cancelled' WHERE id = ?", invoice);
        seller.get("/api/customers/" + customer + "/balance").andExpect(jsonPath("$.balance").value("0.00"))
                .andExpect(jsonPath("$.uninvoicedDeliveryNotes").value("90.00"));
    }

    @Test
    void theBalanceIsCreditInvoicesLessTheirCreditNotesAndAccountPayments() throws Exception {
        long invoice = document("invoice", "INV-1", true, "80.00", null, null);
        document("credit_note", "CN-1", false, "5.00", invoice, null);
        long counterSale = document("invoice", "INV-2", false, "50.00", null, null);
        document("credit_note", "CN-2", false, "7.00", counterSale, null);
        allocate(payment("50.00", null), counterSale, "50.00");
        creditDraft();
        expectBalance("75.00");

        long allocated = payment("20.00", null);
        allocate(allocated, invoice, "20.00");
        payment("10.00", null);
        expectBalance("45.00");

        long reversed = payment("4.00", null);
        payment("4.00", reversed);
        expectBalance("45.00");
    }

    @Test
    void overdueBlocksOnlyWithTheSettingAndOnlyWhatIsStillOpen() throws Exception {
        limit(null);
        long overdue = document("invoice", "INV-OLD", true, "30.00", null, today.minusDays(11));
        confirm(creditDraft()).andExpect(status().isOk());

        setOverdueDays("10");
        long blocked = creditDraft();
        confirm(blocked).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CREDIT_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.context.overdueInvoice").value("INV-OLD-" + customer));

        setOverdueDays("11");
        confirm(blocked).andExpect(status().isOk());

        setOverdueDays("0");
        allocate(payment("30.00", null), overdue, "30.00");
        confirm(creditDraft()).andExpect(status().isOk());
    }

    @Test
    void anOverdueInvoiceClearedByACreditNoteNoLongerBlocks() throws Exception {
        long overdue = document("invoice", "INV-CN", true, "30.00", null, today.minusDays(5));
        document("credit_note", "CN-OLD", false, "30.00", overdue, null);
        setOverdueDays("0");
        confirm(creditDraft()).andExpect(status().isOk());
    }

    @Test
    void anInactiveCustomerIsRefused() throws Exception {
        long sale = creditDraft();
        jdbc.update("UPDATE customers SET is_active = FALSE WHERE id = ?", customer);
        entities.clear();
        confirm(sale).andExpect(jsonPath("$.code").value("CUSTOMER_INACTIVE"));
    }

    @Test
    void theCustomerEndpointsListSalesAndFindOnlyKnownCustomers() throws Exception {
        long sale = creditDraft();
        confirm(sale).andExpect(status().isOk());
        seller.get("/api/customers/" + customer + "/sales").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(sale))
                .andExpect(jsonPath("$.content[0].creditSale").value(true));
        seller.get("/api/customers/999999999/sales").andExpect(status().isNotFound());
        seller.get("/api/customers/999999999/balance").andExpect(status().isNotFound());
    }

    private long creditDraft() throws Exception {
        return draft(null);
    }

    private long draft(String extra) throws Exception {
        long id = JSON.readTree(seller.post("/api/sales", "{\"docType\":\"invoice\",\"creditSale\":true,\"customerId\":"
                + customer + (extra == null ? "" : "," + extra) + "}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        seller.post("/api/sales/" + id + "/lines", "{\"productId\":" + product + ",\"quantity\":\"2\"}")
                .andExpect(status().isOk());
        return id;
    }

    private ResultActions confirm(long id) throws Exception {
        return seller.post("/api/sales/" + id + "/confirm", "{}");
    }

    private void expectBalance(String balance) throws Exception {
        seller.get("/api/customers/" + customer + "/balance").andExpect(jsonPath("$.balance").value(balance));
    }

    private void limit(String value) {
        jdbc.update("UPDATE customers SET credit_limit = ? WHERE id = ?", value == null ? null : new BigDecimal(value),
                customer);
        entities.clear();
    }

    private void setOverdueDays(String value) {
        jdbc.update("UPDATE app_settings SET setting_value = ? WHERE setting_key = 'credit_overdue_block_days'", value);
        entities.clear();
    }

    private long document(String type, String number, boolean credit, String ttc, Long parent, LocalDate due) {
        jdbc.update("""
                INSERT INTO sales_documents (doc_type, number, status, customer_id, parent_id, is_credit_sale, due_date,
                    issued_at, total_ttc, created_by, created_at, updated_at)
                VALUES (?, ?, 'confirmed', ?, ?, ?, ?, UTC_TIMESTAMP(6), ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                type, number + "-" + customer, customer, parent, credit, due, new BigDecimal(ttc), operator);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long payment(String amount, Long reverses) {
        jdbc.update("""
                INSERT INTO customer_payments (customer_id, method, amount, reference, reverses_payment_id,
                    reversal_reason, paid_at, created_by)
                VALUES (?, 'cheque', ?, 'CHQ', ?, ?, UTC_TIMESTAMP(6), ?)""",
                customer, new BigDecimal(amount), reverses, reverses == null ? null : "error", operator);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void allocate(long payment, long document, String amount) {
        jdbc.update("INSERT INTO payment_allocations (payment_id, invoice_id, amount) VALUES (?, ?, ?)", payment, document,
                new BigDecimal(amount));
    }
}
