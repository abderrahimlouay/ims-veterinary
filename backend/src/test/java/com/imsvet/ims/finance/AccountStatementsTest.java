package com.imsvet.ims.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The account statement (BR-PAY-007, ADR-134 item 6) and the balance (BR-PAY-004, DR-006): the opening balance at the
 * start date, the credit invoices, their credit notes, payments on account and reversals in date order with a running
 * balance; counter sales, their payments and cancelled documents absent; uninvoiced credit delivery notes apart; and
 * the closing balance equal to the balance of {@code /balance} (the credit check's) and to a recomputation by the
 * definition of ADR-118 on random fixtures.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AccountStatementsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    private ApiClient accountant;
    private long operator;
    private long customer;

    @BeforeEach
    void setUp() throws Exception {
        operator = new TestLots(jdbc).fixtureUser();
        TestUser user = new TestUsers(jdbc, encoder).create("Accountant");
        accountant = ApiClient.create(context, "198.51.100.231");
        accountant.login(user.username(), user.password()).andExpect(status().isOk());
        jdbc.update("INSERT INTO customers (name, created_at, updated_at) VALUES (?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                "Ferme Relevé " + System.nanoTime());
        customer = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Test
    void theStatementListsTheBalanceEntriesInDateOrderWithARunningBalance() throws Exception {
        long january = document("invoice", "INV-1", null, true, "confirmed", "2026-01-10 10:00:00", "100.00");
        document("credit_note", "CN-1", january, true, "confirmed", "2026-01-20 10:00:00", "15.00");
        long receipt = document("receipt", "RCT-1", null, false, "confirmed", "2026-02-01 10:00:00", "40.00");
        long counter = payment("cash", "40.00", null, "2026-02-01 10:00:01");
        allocate(counter, receipt, "40.00");
        long march = document("invoice", "INV-2", null, true, "confirmed", "2026-03-05 10:00:00", "60.00");
        document("invoice", "INV-X", null, true, "cancelled", "2026-03-06 10:00:00", "999.00");
        long cheque = payment("cheque", "50.00", "CHQ-7", "2026-03-10 10:00:00");
        allocate(cheque, january, "50.00");
        long wrong = payment("bank_transfer", "30.00", "VIR-8", "2026-03-12 10:00:00");
        reversal(wrong, "2026-03-13 10:00:00");
        document("delivery_note", "DN-1", null, true, "confirmed", "2026-03-14 10:00:00", "12.00");

        accountant.get("/api/customers/" + customer + "/statement?from=2026-02-01&to=2026-03-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.id").value(customer))
                .andExpect(jsonPath("$.openingBalance").value("85.00"))
                .andExpect(jsonPath("$.lines.length()").value(4))
                .andExpect(jsonPath("$.lines[0].kind").value("invoice"))
                .andExpect(jsonPath("$.lines[0].documentId").value(march))
                .andExpect(jsonPath("$.lines[0].date").value("2026-03-05"))
                .andExpect(jsonPath("$.lines[0].debit").value("60.00"))
                .andExpect(jsonPath("$.lines[0].balance").value("145.00"))
                .andExpect(jsonPath("$.lines[1].kind").value("payment"))
                .andExpect(jsonPath("$.lines[1].paymentId").value(cheque))
                .andExpect(jsonPath("$.lines[1].reference").value("CHQ-7"))
                .andExpect(jsonPath("$.lines[1].credit").value("50.00"))
                .andExpect(jsonPath("$.lines[1].balance").value("95.00"))
                .andExpect(jsonPath("$.lines[2].kind").value("payment"))
                .andExpect(jsonPath("$.lines[2].balance").value("65.00"))
                .andExpect(jsonPath("$.lines[3].kind").value("reversal"))
                .andExpect(jsonPath("$.lines[3].debit").value("30.00"))
                .andExpect(jsonPath("$.lines[3].balance").value("95.00"))
                .andExpect(jsonPath("$.totalDebit").value("90.00"))
                .andExpect(jsonPath("$.totalCredit").value("80.00"))
                .andExpect(jsonPath("$.closingBalance").value("95.00"))
                .andExpect(jsonPath("$.uninvoicedDeliveryNotes").value("12.00"));
        accountant.get("/api/customers/" + customer + "/statement?to=2026-01-31")
                .andExpect(jsonPath("$.from").isEmpty())
                .andExpect(jsonPath("$.openingBalance").value("0.00"))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[1].kind").value("credit_note"))
                .andExpect(jsonPath("$.lines[1].credit").value("15.00"))
                .andExpect(jsonPath("$.closingBalance").value("85.00"));
        accountant.get("/api/customers/" + customer + "/balance").andExpect(jsonPath("$.balance").value("95.00"));
    }

    @Test
    void aPeriodEndingBeforeItStartsIsRefused() throws Exception {
        accountant.get("/api/customers/" + customer + "/statement?from=2026-03-02&to=2026-03-01")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("from"))
                .andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        accountant.get("/api/customers/999999999/statement").andExpect(status().isNotFound());
    }

    @RepeatedTest(5)
    void theClosingBalanceEqualsTheBalanceAndTheDefinitionOnRandomFixtures() throws Exception {
        Random random = new Random();
        BigDecimal expected = BigDecimal.ZERO;
        for (int i = 0; i < 12; i++) {
            String at = "2026-0" + (1 + random.nextInt(9)) + "-1" + random.nextInt(10) + " 1" + random.nextInt(10)
                    + ":00:00";
            BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(50000), 2);
            switch (random.nextInt(5)) {
                case 0 -> {
                    long invoice = document("invoice", "INV-R" + i, null, true, "confirmed", at, amount.toPlainString());
                    expected = expected.add(amount);
                    if (random.nextBoolean()) {
                        BigDecimal part = amount.divide(BigDecimal.valueOf(3), 2, RoundingMode.DOWN);
                        document("credit_note", "CN-R" + i, invoice, true, "confirmed", at, part.toPlainString());
                        expected = expected.subtract(part);
                    }
                }
                case 1 -> {
                    payment("cheque", amount.toPlainString(), "C" + i, at);
                    expected = expected.subtract(amount);
                }
                case 2 -> {
                    long paid = payment("cheque", amount.toPlainString(), "R" + i, at);
                    reversal(paid, at);
                }
                case 3 -> {
                    long receipt = document("receipt", "RCT-R" + i, null, false, "confirmed", at, amount.toPlainString());
                    allocate(payment("cash", amount.toPlainString(), null, at), receipt, amount.toPlainString());
                }
                default -> document("invoice", "INV-C" + i, null, true, "cancelled", at, amount.toPlainString());
            }
        }

        JsonNode statement = JSON.readTree(accountant.get("/api/customers/" + customer + "/statement")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode balance = JSON.readTree(accountant.get("/api/customers/" + customer + "/balance")
                .andReturn().getResponse().getContentAsString());

        assertThat(new BigDecimal(statement.get("closingBalance").asString())).isEqualByComparingTo(expected);
        assertThat(new BigDecimal(balance.get("balance").asString())).isEqualByComparingTo(expected);
        JsonNode lines = statement.get("lines");
        if (!lines.isEmpty()) {
            assertThat(new BigDecimal(lines.get(lines.size() - 1).get("balance").asString()))
                    .isEqualByComparingTo(expected);
        }
    }

    private long document(String type, String number, Long parent, boolean credit, String state, String at, String ttc) {
        jdbc.update("""
                INSERT INTO sales_documents (doc_type, number, status, customer_id, parent_id, is_credit_sale, due_date,
                    issued_at, total_ttc, created_by, created_at, updated_at, cancelled_by, cancelled_at, cancel_reason)
                VALUES (?, ?, ?, ?, ?, ?, DATE(?), ?, ?, ?, ?, ?, ?, ?, ?)""",
                type, number + "-" + customer, state, customer, parent, credit, at, at, new BigDecimal(ttc), operator,
                at, at, "cancelled".equals(state) ? operator : null, "cancelled".equals(state) ? at : null,
                "cancelled".equals(state) ? "Erreur" : null);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long payment(String method, String amount, String reference, String at) {
        Long session = null;
        if ("cash".equals(method)) {
            jdbc.update("""
                    INSERT INTO cash_sessions (cashier_id, opening_amount, opened_at, status)
                    VALUES (?, 0, ?, 'closed')""", operator, at);
            session = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        }
        jdbc.update("""
                INSERT INTO customer_payments (customer_id, method, amount, reference, cash_session_id, paid_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", customer, method, new BigDecimal(amount), reference, session, at,
                operator);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void reversal(long payment, String at) {
        jdbc.update("""
                INSERT INTO customer_payments (customer_id, method, amount, reference, cash_session_id, reverses_payment_id,
                    reversal_reason, paid_at, created_by)
                SELECT customer_id, method, amount, reference, cash_session_id, id, 'Erreur', ?, ? FROM customer_payments
                WHERE id = ?""", at, operator, payment);
    }

    private void allocate(long payment, long document, String amount) {
        jdbc.update("""
                INSERT INTO payment_allocations (payment_id, invoice_id, amount, created_at, created_by)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6), ?)""", payment, document, new BigDecimal(amount), operator);
    }
}
