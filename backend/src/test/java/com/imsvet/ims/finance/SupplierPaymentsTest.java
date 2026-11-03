package com.imsvet.ims.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
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
 * Supplier payments (ADR-132, ADR-135, W1c, BR-PAY-009/010/013/014/015): one payment split across several invoices, as
 * asked or oldest due first, within each open amount and the payment; the rest an advance applied later; the payables
 * and the balance (invoices − credit notes − payments in force); cash out of the payer's own session within its cash;
 * a reversal once, with its reason, reopening the invoices and bringing the cash back; an invoice a payment settles
 * cannot be cancelled until the payment is reversed.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SupplierPaymentsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    private ApiClient accountant;
    private ApiClient admin;
    private long supplier;

    @BeforeEach
    void setUp() throws Exception {
        TestUser user = new TestUsers(jdbc, encoder).create("Accountant");
        accountant = ApiClient.create(context, "198.51.100.251");
        accountant.login(user.username(), user.password()).andExpect(status().isOk());
        TestUser administrator = new TestUsers(jdbc, encoder).create("Administrator");
        admin = ApiClient.create(context, "198.51.100.252");
        admin.login(administrator.username(), administrator.password()).andExpect(status().isOk());
        supplier = supplier("Labo Paiement");
    }

    @Test
    void aPaymentSettlesTheOldestDueFirstAndTheRestIsAnAdvanceAppliedLater() throws Exception {
        long later = invoice(supplier, "FA-B", "2026-10-15", "50");
        long older = invoice(supplier, "FA-A", "2026-10-01", "100");
        long note = id(accountant.post("/api/supplier-invoices", "{\"supplierId\":" + supplier
                + ",\"invoiceNumber\":\"AV-1\",\"invoiceDate\":\"2026-10-02\",\"totalTtc\":\"20\",\"creditNote\":true}"));

        long cheque = id(pay(body(supplier, "cheque", "120", null), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.allocations[0].invoiceId").value(older))
                .andExpect(jsonPath("$.allocations[0].amount").value("100.00"))
                .andExpect(jsonPath("$.allocations[1].invoiceId").value(later))
                .andExpect(jsonPath("$.allocations[1].amount").value("20.00"))
                .andExpect(jsonPath("$.unallocated").value("0.00"))
                .andExpect(jsonPath("$.supplierBalance").value("10.00")));
        long advance = id(pay(body(supplier, "bank_transfer", "40", "[]"), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.unallocated").value("40.00"))
                .andExpect(jsonPath("$.supplierBalance").value("-30.00")));

        accountant.get("/api/suppliers/" + supplier + "/payables").andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value("-30.00"))
                .andExpect(jsonPath("$.invoices.length()").value(1))
                .andExpect(jsonPath("$.invoices[0].id").value(later))
                .andExpect(jsonPath("$.invoices[0].open").value("30.00"))
                .andExpect(jsonPath("$.creditNotes[0].id").value(note))
                .andExpect(jsonPath("$.creditNotes[0].unapplied").value("20.00"))
                .andExpect(jsonPath("$.advances[0].paymentId").value(advance))
                .andExpect(jsonPath("$.advances[0].unallocated").value("40.00"));
        apply(advance, "[{\"invoiceId\":" + later + ",\"amount\":\"30.01\"}]").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PAYMENT_ALLOCATION_EXCEEDED"))
                .andExpect(jsonPath("$.context.open").value("30.00"));
        apply(advance, "[{\"invoiceId\":" + later + ",\"amount\":\"30\"}]").andExpect(status().isOk())
                .andExpect(jsonPath("$.unallocated").value("10.00"));
        accountant.get("/api/suppliers/" + supplier + "/payables").andExpect(jsonPath("$.invoices").isEmpty())
                .andExpect(jsonPath("$.advances[0].unallocated").value("10.00"));
        accountant.get("/api/supplier-invoices/" + later).andExpect(jsonPath("$.open").value("0.00"))
                .andExpect(jsonPath("$.payments.length()").value(2))
                .andExpect(jsonPath("$.payments[0].paymentId").value(cheque));
        accountant.get("/api/payments/suppliers?supplierId=" + supplier)
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void onlyTheSuppliersRecordedInvoicesCanBeSettledWithinTheirOpenAmountAndThePayment() throws Exception {
        long invoice = invoice(supplier, "FA-E", "2026-10-01", "100");
        long others = invoice(supplier("Autre"), "FA-O", "2026-10-01", "10");
        long note = id(accountant.post("/api/supplier-invoices", "{\"supplierId\":" + supplier
                + ",\"invoiceNumber\":\"AV-E\",\"invoiceDate\":\"2026-10-02\",\"totalTtc\":\"5\",\"creditNote\":true}"));

        pay(body(supplier, "cheque", "50", "[{\"invoiceId\":" + invoice + ",\"amount\":\"60\"}]"), null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.context.available").value("50.00"));
        assertField(body(supplier, "cheque", "50", "[{\"invoiceId\":" + others + ",\"amount\":\"5\"}]"),
                "allocations[0].invoiceId", "NOT_ALLOWED");
        assertField(body(supplier, "cheque", "50", "[{\"invoiceId\":" + note + ",\"amount\":\"5\"}]"),
                "allocations[0].invoiceId", "NOT_ALLOWED");
        assertField(body(supplier, "cheque", "0", null), "amount", "POSITIVE");
        assertField("{\"supplierId\":" + supplier + ",\"method\":\"cheque\",\"amount\":\"5\"}", "reference",
                "REQUIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM supplier_payments WHERE supplier_id = ?", Integer.class,
                supplier)).isZero();
        admin.post("/api/suppliers/" + supplier + "/deactivate", "").andExpect(status().isOk());
        pay(body(supplier, "cheque", "5", null), null).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SUPPLIER_INACTIVE"));
    }

    @Test
    void cashGoesOutOfThePayersOwnSessionWithinItsCashAndComesBackOnReversal() throws Exception {
        String cash = "{\"supplierId\":" + supplier + ",\"method\":\"cash\",\"amount\":\"30\"}";
        pay(cash, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASH_SESSION_REQUIRED"));
        accountant.post("/api/cash-sessions/open", "{\"openingAmount\":\"25\"}").andExpect(status().isCreated());
        pay(cash, null).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CASH_INSUFFICIENT"));
        accountant.get("/api/cash-sessions/current").andExpect(jsonPath("$.expectedAmount").value("25.00"));

        long payment = id(pay("{\"supplierId\":" + supplier + ",\"method\":\"cash\",\"amount\":\"20\"}", null)
                .andExpect(status().isCreated()));
        accountant.get("/api/cash-sessions/current").andExpect(jsonPath("$.expectedAmount").value("5.00"));
        assertThat(jdbc.queryForObject("SELECT direction FROM cash_movements WHERE supplier_payment_id = ?",
                String.class, payment)).isEqualTo("out");

        reverse(payment, "Erreur de fournisseur").andExpect(status().isOk());
        accountant.get("/api/cash-sessions/current").andExpect(jsonPath("$.expectedAmount").value("25.00"));
    }

    @Test
    void aReversalReopensTheInvoiceWhichCanThenBeCancelledAndHappensOnce() throws Exception {
        long invoice = invoice(supplier, "FA-R", "2026-10-01", "100");
        long payment = id(pay(body(supplier, "cheque", "100", null), null).andExpect(status().isCreated()));

        accountant.post("/api/supplier-invoices/" + invoice + "/cancel", "{\"reason\":\"Erreur\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVOICE_HAS_SETTLEMENTS"))
                .andExpect(jsonPath("$.context.blockedBy").value("payment"))
                .andExpect(jsonPath("$.context.paymentId").value(payment));
        reverse(payment, " ").andExpect(status().isBadRequest());
        reverse(payment, "Chèque rejeté").andExpect(status().isOk())
                .andExpect(jsonPath("$.reversedBy").isNumber())
                .andExpect(jsonPath("$.unallocated").value("0.00"))
                .andExpect(jsonPath("$.supplierBalance").value("100.00"));

        accountant.get("/api/supplier-invoices/" + invoice).andExpect(jsonPath("$.open").value("100.00"))
                .andExpect(jsonPath("$.payments").isEmpty());
        assertThat(jdbc.queryForObject("SELECT new_values FROM audit_log WHERE action = 'PAYMENT_REVERSE'"
                + " AND entity = 'supplier_payments' AND entity_id = ?", String.class, payment)).contains("Chèque rejeté");
        reverse(payment, "Encore").andExpect(status().isConflict())
                .andExpect(jsonPath("$.context.state").value("reversed"));
        apply(payment, "[{\"invoiceId\":" + invoice + ",\"amount\":\"1\"}]").andExpect(status().isConflict())
                .andExpect(jsonPath("$.context.state").value("reversed"));
        accountant.post("/api/supplier-invoices/" + invoice + "/cancel", "{\"reason\":\"Erreur\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("cancelled"));
        accountant.get("/api/suppliers/" + supplier + "/payables").andExpect(jsonPath("$.balance").value("0.00"));
    }

    @Test
    void aReplayWithTheSameKeyReturnsTheFirstPayment() throws Exception {
        invoice(supplier, "FA-I", "2026-10-01", "100");
        String key = UUID.randomUUID().toString();
        long first = id(pay(body(supplier, "cheque", "30", null), key).andExpect(status().isCreated()));
        long second = id(pay(body(supplier, "cheque", "30", null), key).andExpect(status().isCreated()));

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM supplier_payments WHERE supplier_id = ?", Integer.class,
                supplier)).isEqualTo(1);
    }

    private ResultActions pay(String body, String key) throws Exception {
        var request = MockMvcRequestBuilders.post("/api/payments/suppliers").contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return accountant.perform(request, true);
    }

    private ResultActions apply(long payment, String allocations) throws Exception {
        return accountant.post("/api/payments/suppliers/" + payment + "/allocations",
                "{\"allocations\":" + allocations + "}");
    }

    private ResultActions reverse(long payment, String reason) throws Exception {
        return accountant.post("/api/payments/suppliers/" + payment + "/reverse", "{\"reason\":\"" + reason + "\"}");
    }

    private void assertField(String body, String field, String code) throws Exception {
        pay(body, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code));
    }

    private static String body(long supplier, String method, String amount, String allocations) {
        return "{\"supplierId\":" + supplier + ",\"method\":\"" + method + "\",\"amount\":\"" + amount
                + "\",\"reference\":\"REF\"" + (allocations == null ? "" : ",\"allocations\":" + allocations) + "}";
    }

    private long invoice(long owner, String number, String date, String total) throws Exception {
        return id(accountant.post("/api/supplier-invoices", "{\"supplierId\":" + owner + ",\"invoiceNumber\":\""
                + number + "\",\"invoiceDate\":\"" + date + "\",\"totalTtc\":\"" + total + "\"}")
                .andExpect(status().isCreated()));
    }

    private long supplier(String name) {
        String unique = name + " " + System.nanoTime();
        jdbc.update("INSERT INTO suppliers (name, created_at, updated_at) VALUES (?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                unique);
        return jdbc.queryForObject("SELECT id FROM suppliers WHERE name = ?", Long.class, unique);
    }

    private static long id(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
