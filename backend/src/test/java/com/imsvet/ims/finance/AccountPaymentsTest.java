package com.imsvet.ims.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Payments on account (ADR-134, W8, BR-PAY-001/002/006/010/012): oldest-first allocation when none is given, the rest an
 * advance that lowers the balance and is applied later by new rows; explicit allocations within each invoice's open
 * amount (TTC − credit notes − payments in force) and the payment; only confirmed credit invoices of that customer; cash
 * through the user's own open session; an inactive customer refused; an idempotent replay; a counter payment never has
 * an advance; and a credit invoice that a payment settles can no longer be cancelled (ADR-119 as amended).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AccountPaymentsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private BusinessCalendar calendar;

    private ApiClient accountant;
    private ApiClient admin;
    private long operator;
    private long customer;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        today = calendar.today();
        operator = new TestLots(jdbc).fixtureUser();
        TestUser user = new TestUsers(jdbc, encoder).create("Accountant");
        accountant = ApiClient.create(context, "198.51.100.221");
        accountant.login(user.username(), user.password()).andExpect(status().isOk());
        TestUser administrator = new TestUsers(jdbc, encoder).create("Administrator");
        admin = ApiClient.create(context, "198.51.100.222");
        admin.login(administrator.username(), administrator.password()).andExpect(status().isOk());
        customer = customer();
    }

    @Test
    void withoutAllocationsThePaymentSettlesTheOldestDueFirstAndTheRestIsAnAdvance() throws Exception {
        long newest = invoice("INV-C", today.plusDays(30), "80.00");
        long oldest = invoice("INV-A", today.minusDays(10), "100.00");
        long middle = invoice("INV-B", today, "50.00");
        creditNote(oldest, "20.00");

        long payment = id(pay("{\"customerId\":" + customer + ",\"method\":\"cheque\",\"amount\":\"250\","
                + "\"reference\":\"CHQ-1\"}", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.customer.id").value(customer))
                .andExpect(jsonPath("$.allocations[0].invoiceId").value(oldest))
                .andExpect(jsonPath("$.allocations[0].amount").value("80.00"))
                .andExpect(jsonPath("$.allocations[1].invoiceId").value(middle))
                .andExpect(jsonPath("$.allocations[1].amount").value("50.00"))
                .andExpect(jsonPath("$.allocations[2].invoiceId").value(newest))
                .andExpect(jsonPath("$.allocations[2].amount").value("80.00"))
                .andExpect(jsonPath("$.unallocated").value("40.00"))
                .andExpect(jsonPath("$.customerBalance").value("-40.00")));

        accountant.get("/api/customers/" + customer + "/open-invoices").andExpect(status().isOk())
                .andExpect(jsonPath("$.invoices").isEmpty())
                .andExpect(jsonPath("$.advances[0].paymentId").value(payment))
                .andExpect(jsonPath("$.advances[0].unallocated").value("40.00"));
        accountant.get("/api/customers/" + customer + "/balance").andExpect(jsonPath("$.balance").value("-40.00"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_movements WHERE customer_payment_id = ?", Integer.class,
                payment)).isZero();
    }

    @Test
    void explicitAllocationsStayWithinEachOpenAmountAndThePaymentOrNothingIsWritten() throws Exception {
        long invoice = invoice("INV-E", today, "100.00");
        creditNote(invoice, "30.00");
        long receipt = document("receipt", "RCT-E", customer, null, false, null, "10.00");
        long otherCustomers = document("invoice", "INV-O", customer(), null, true, today, "10.00");

        pay(body("100", "[{\"invoiceId\":" + invoice + ",\"amount\":\"70.01\"}]"), null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PAYMENT_ALLOCATION_EXCEEDED"))
                .andExpect(jsonPath("$.context.open").value("70.00"))
                .andExpect(jsonPath("$.context.requested").value("70.01"));
        pay(body("50", "[{\"invoiceId\":" + invoice + ",\"amount\":\"60\"}]"), null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PAYMENT_ALLOCATION_EXCEEDED"))
                .andExpect(jsonPath("$.context.available").value("50.00"));
        assertField(body("50", "[{\"invoiceId\":" + receipt + ",\"amount\":\"5\"}]"), "allocations[0].invoiceId",
                "NOT_ALLOWED");
        assertField(body("50", "[{\"invoiceId\":" + otherCustomers + ",\"amount\":\"5\"}]"),
                "allocations[0].invoiceId", "NOT_ALLOWED");
        assertField(body("50", "[{\"invoiceId\":" + invoice + ",\"amount\":\"5\"},{\"invoiceId\":" + invoice
                + ",\"amount\":\"5\"}]"), "allocations[1].invoiceId", "DUPLICATE");
        assertField(body("50", "[{\"invoiceId\":" + invoice + ",\"amount\":\"0\"}]"), "allocations[0].amount",
                "POSITIVE");
        assertField("{\"customerId\":" + customer + ",\"method\":\"cheque\",\"amount\":\"5\"}", "reference", "REQUIRED");
        assertThat(payments()).isZero();

        pay(body("100", "[{\"invoiceId\":" + invoice + ",\"amount\":\"70\"}]"), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.allocations[0].invoiceNumber").value("INV-E-" + customer))
                .andExpect(jsonPath("$.unallocated").value("30.00"))
                .andExpect(jsonPath("$.customerBalance").value("-30.00"));
    }

    @Test
    void anAdvanceIsAppliedLaterByNewRowsEvenTwiceToTheSameInvoiceButNeverBeyondWhatIsLeft() throws Exception {
        long payment = id(pay(body("60", "[]"), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.allocations").isEmpty())
                .andExpect(jsonPath("$.unallocated").value("60.00"))
                .andExpect(jsonPath("$.customerBalance").value("-60.00")));
        long invoice = invoice("INV-L", today, "100.00");

        apply(payment, "[{\"invoiceId\":" + invoice + ",\"amount\":\"25\"}]", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.unallocated").value("35.00"));
        apply(payment, "[{\"invoiceId\":" + invoice + ",\"amount\":\"35.01\"}]", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PAYMENT_ALLOCATION_EXCEEDED"))
                .andExpect(jsonPath("$.context.available").value("35.00"));
        apply(payment, "[{\"invoiceId\":" + invoice + ",\"amount\":\"35\"}]", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.allocations.length()").value(2))
                .andExpect(jsonPath("$.unallocated").value("0.00"))
                .andExpect(jsonPath("$.customerBalance").value("40.00"));
        accountant.get("/api/customers/" + customer + "/open-invoices")
                .andExpect(jsonPath("$.invoices[0].open").value("40.00"))
                .andExpect(jsonPath("$.advances").isEmpty());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_allocations WHERE payment_id = ? AND invoice_id = ?",
                Integer.class, payment, invoice)).isEqualTo(2);
    }

    @Test
    void aCounterPaymentHasNoAdvanceToApply() throws Exception {
        long receipt = document("receipt", "RCT-K", customer, null, false, null, "10.00");
        jdbc.update("""
                INSERT INTO customer_payments (customer_id, method, amount, reference, paid_at, created_by)
                VALUES (?, 'cheque', 10, 'C', UTC_TIMESTAMP(6), ?)""", customer, operator);
        long counter = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO payment_allocations (payment_id, invoice_id, amount, created_at, created_by)"
                + " VALUES (?, ?, 10, UTC_TIMESTAMP(6), ?)", counter, receipt, operator);
        long invoice = invoice("INV-K", today, "10.00");

        apply(counter, "[{\"invoiceId\":" + invoice + ",\"amount\":\"1\"}]", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"))
                .andExpect(jsonPath("$.context.state").value("counter_payment"));
        accountant.get("/api/customers/" + customer + "/open-invoices")
                .andExpect(jsonPath("$.invoices[0].open").value("10.00"))
                .andExpect(jsonPath("$.advances").isEmpty());
    }

    @Test
    void cashGoesIntoTheUsersOwnOpenSession() throws Exception {
        String cash = "{\"customerId\":" + customer + ",\"method\":\"cash\",\"amount\":\"15\"}";
        pay(cash, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASH_SESSION_REQUIRED"));
        assertThat(payments()).isZero();
        accountant.post("/api/cash-sessions/open", "{\"openingAmount\":\"10\"}").andExpect(status().isCreated());

        long payment = id(pay(cash, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.cashSessionId").isNumber()));

        assertThat(jdbc.queryForObject("SELECT direction FROM cash_movements WHERE customer_payment_id = ?", String.class,
                payment)).isEqualTo("in");
        accountant.get("/api/cash-sessions/current").andExpect(jsonPath("$.expectedAmount").value("25.00"));
    }

    @Test
    void anInactiveCustomerIsRefusedAndAnUnknownOneIsNotFound() throws Exception {
        jdbc.update("UPDATE customers SET is_active = FALSE WHERE id = ?", customer);
        pay(body("10", "[]"), null).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CUSTOMER_INACTIVE"));
        pay("{\"customerId\":999999999,\"method\":\"cheque\",\"amount\":\"10\",\"reference\":\"C\"}", null)
                .andExpect(status().isNotFound());
        assertThat(payments()).isZero();
    }

    @Test
    void aReplayWithTheSameKeyReturnsTheFirstPayment() throws Exception {
        long invoice = invoice("INV-I", today, "30.00");
        String key = UUID.randomUUID().toString();
        long first = id(pay(body("20", null), key).andExpect(status().isCreated()));
        long second = id(pay(body("20", null), key).andExpect(status().isCreated()));

        assertThat(second).isEqualTo(first);
        assertThat(payments()).isEqualTo(1);
        accountant.get("/api/payments/customers/" + first).andExpect(jsonPath("$.allocations[0].invoiceId")
                .value(invoice));
        accountant.get("/api/payments/customers?customerId=" + customer)
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].reversed").value(false));
    }

    @Test
    void aCreditInvoiceThatAPaymentSettlesCannotBeCancelled() throws Exception {
        long invoice = invoice("INV-X", today, "30.00");
        long payment = id(pay(body("10", null), null).andExpect(status().isCreated()));

        admin.post("/api/sales/" + invoice + "/cancel", "{\"reason\":\"Erreur\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SALE_NOT_CANCELLABLE"))
                .andExpect(jsonPath("$.context.blockedBy").value("payment"))
                .andExpect(jsonPath("$.context.paymentId").value(payment));
        assertThat(jdbc.queryForObject("SELECT status FROM sales_documents WHERE id = ?", String.class, invoice))
                .isEqualTo("confirmed");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_payments WHERE reverses_payment_id = ?",
                Integer.class, payment)).isZero();
    }

    private ResultActions pay(String body, String key) throws Exception {
        var request = MockMvcRequestBuilders.post("/api/payments/customers").contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return accountant.perform(request, true);
    }

    private ResultActions apply(long payment, String allocations, String key) throws Exception {
        var request = MockMvcRequestBuilders.post("/api/payments/customers/" + payment + "/allocations")
                .contentType(MediaType.APPLICATION_JSON).content("{\"allocations\":" + allocations + "}");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return accountant.perform(request, true);
    }

    private void assertField(String body, String field, String code) throws Exception {
        pay(body, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code));
    }

    private String body(String amount, String allocations) {
        return "{\"customerId\":" + customer + ",\"method\":\"bank_transfer\",\"amount\":\"" + amount
                + "\",\"reference\":\"VIR-1\"" + (allocations == null ? "" : ",\"allocations\":" + allocations) + "}";
    }

    private long payments() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM customer_payments WHERE customer_id = ?", Long.class, customer);
    }

    private long customer() {
        jdbc.update("INSERT INTO customers (name, created_at, updated_at) VALUES (?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                "Ferme Compte " + System.nanoTime());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long invoice(String number, LocalDate due, String ttc) {
        return document("invoice", number, customer, null, true, due, ttc);
    }

    private void creditNote(long invoice, String ttc) {
        document("credit_note", "CN-" + invoice, customer, invoice, true, null, ttc);
    }

    private long document(String type, String number, long owner, Long parent, boolean credit, LocalDate due,
            String ttc) {
        jdbc.update("""
                INSERT INTO sales_documents (doc_type, number, status, customer_id, parent_id, is_credit_sale, due_date,
                    issued_at, total_ttc, created_by, created_at, updated_at)
                VALUES (?, ?, 'confirmed', ?, ?, ?, ?, UTC_TIMESTAMP(6), ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                type, number + "-" + owner, owner, parent, credit, due, new BigDecimal(ttc), operator);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private static long id(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
