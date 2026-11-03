package com.imsvet.ims.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
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
 * Supplier invoices and credit notes (ADR-133, W1c, BR-PAY-008/011/015): the supplier's number unique per supplier,
 * cancelled ones included; the due date from the payment terms; an optional confirmed receipt of that supplier, a
 * different total being a warning only; a credit note with an optional return of that supplier, applied to its
 * supplier's recorded invoices within what each has left and its own; a wrong entry cancelled with its reason while
 * nothing settles it, audited.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SupplierInvoicesTest {

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
    private long product;

    @BeforeEach
    void setUp() throws Exception {
        TestUser user = new TestUsers(jdbc, encoder).create("Accountant");
        accountant = ApiClient.create(context, "198.51.100.241");
        accountant.login(user.username(), user.password()).andExpect(status().isOk());
        TestUser administrator = new TestUsers(jdbc, encoder).create("Administrator");
        admin = ApiClient.create(context, "198.51.100.242");
        admin.login(administrator.username(), administrator.password()).andExpect(status().isOk());
        supplier = supplier("Labo Facture", 30);
        product = new TestLots(jdbc).product("SINV-" + System.nanoTime() % 100000, "Facturé");
        jdbc.update("UPDATE products SET vat_rate = 19 WHERE id = ?", product);
    }

    @Test
    void anInvoiceTakesItsDueDateFromThePaymentTermsAndADifferentReceiptTotalIsOnlyAWarning() throws Exception {
        long receipt = receipt(supplier, "R-1");

        long first = id(record(invoice("FA-001", "30.00", receipt, null, false, null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplier.id").value(supplier))
                .andExpect(jsonPath("$.dueDate").value("2026-10-31"))
                .andExpect(jsonPath("$.receipt.id").value(receipt))
                .andExpect(jsonPath("$.open").value("30.00"))
                .andExpect(jsonPath("$.status").value("recorded"))
                .andExpect(jsonPath("$.warnings[0].code").value("RECEIPT_TOTAL_DIFFERS"))
                .andExpect(jsonPath("$.warnings[0].context.receiptValue").value("29.75")));
        record(invoice("FA-002", "29.75", receipt, null, false, "2026-11-15")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.dueDate").value("2026-11-15"))
                .andExpect(jsonPath("$.warnings").isEmpty());

        accountant.get("/api/supplier-invoices/" + first).andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceNumber").value("FA-001")).andExpect(jsonPath("$.warnings").isEmpty());
    }

    @Test
    void theNumberIsUniquePerSupplierAndTheReferencesMustBeThatSuppliers() throws Exception {
        long other = supplier("Autre labo", 0);
        long othersReceipt = receipt(other, "R-O");
        long cancelledReceipt = receipt(supplier, "R-C");
        admin.post("/api/goods-receipts/" + cancelledReceipt + "/cancel", "{\"reason\":\"Erreur\"}")
                .andExpect(status().isOk());
        long othersReturn = supplierReturn(other);
        record(invoice("FA-9", "10", null, null, false, null)).andExpect(status().isCreated());

        record(invoice(" fa-9 ", "10", null, null, false, null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
        accountant.post("/api/supplier-invoices", "{\"supplierId\":" + other + ",\"invoiceNumber\":\"FA-9\","
                + "\"invoiceDate\":\"2026-10-01\",\"totalTtc\":\"10\"}").andExpect(status().isCreated());
        assertField(invoice("FA-10", "10", othersReceipt, null, false, null), "receiptId", "OTHER_SUPPLIER");
        assertField(invoice("FA-11", "10", cancelledReceipt, null, false, null), "receiptId", "NOT_CONFIRMED");
        assertField(invoice("FA-12", "10", null, othersReturn, false, null), "supplierReturnId", "NOT_ALLOWED");
        assertField(invoice("AV-1", "10", null, othersReturn, true, null), "supplierReturnId", "OTHER_SUPPLIER");
        assertField(invoice("FA-13", "-1", null, null, false, null), "totalTtc", "NOT_NEGATIVE");
        assertField("{\"supplierId\":" + supplier + ",\"totalTtc\":\"1\"}", "invoiceNumber", "REQUIRED");
        admin.post("/api/suppliers/" + supplier + "/deactivate", "").andExpect(status().isOk());
        record(invoice("FA-14", "10", null, null, false, null)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SUPPLIER_INACTIVE"));
    }

    @Test
    void aCreditNoteIsAppliedToItsSuppliersInvoicesWithinWhatEachHasLeft() throws Exception {
        long invoice = id(record(invoice("FA-20", "100", null, null, false, null)));
        long small = id(record(invoice("FA-21", "5", null, null, false, null)));
        long returned = supplierReturn(supplier);
        long note = id(record(invoice("AV-20", "30", null, returned, true, null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.creditNote").value(true))
                .andExpect(jsonPath("$.supplierReturn.id").value(returned)));
        long othersInvoice = id(accountant.post("/api/supplier-invoices", "{\"supplierId\":" + supplier("Tiers", 0)
                + ",\"invoiceNumber\":\"FA-T\",\"invoiceDate\":\"2026-10-01\",\"totalTtc\":\"50\"}"));

        apply(note, "[{\"invoiceId\":" + small + ",\"amount\":\"5.01\"}]").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PAYMENT_ALLOCATION_EXCEEDED"))
                .andExpect(jsonPath("$.context.open").value("5.00"));
        apply(note, "[{\"invoiceId\":" + invoice + ",\"amount\":\"31\"}]").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.context.available").value("30.00"));
        apply(note, "[{\"invoiceId\":" + othersInvoice + ",\"amount\":\"1\"}]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_ALLOWED"));
        apply(invoice, "[{\"invoiceId\":" + small + ",\"amount\":\"1\"}]").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        apply(note, "[{\"invoiceId\":" + invoice + ",\"amount\":\"20\"},{\"invoiceId\":" + small
                + ",\"amount\":\"5\"}]").andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value("5.00"))
                .andExpect(jsonPath("$.applications.length()").value(2))
                .andExpect(jsonPath("$.applications[0].otherNumber").value("FA-20"));
        apply(note, "[{\"invoiceId\":" + invoice + ",\"amount\":\"5\"}]").andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value("0.00"));
        accountant.get("/api/supplier-invoices/" + invoice).andExpect(jsonPath("$.open").value("75.00"))
                .andExpect(jsonPath("$.applications[1].otherId").value(note));
        accountant.get("/api/supplier-invoices?supplierId=" + supplier + "&open=true")
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(invoice));
    }

    @Test
    void aWrongEntryIsCancelledWithItsReasonOnlyWhileNothingSettlesIt() throws Exception {
        long invoice = id(record(invoice("FA-30", "100", null, null, false, null)));
        long note = id(record(invoice("AV-30", "10", null, null, true, null)));
        long wrong = id(record(invoice("FA-31", "40", null, null, false, null)));
        apply(note, "[{\"invoiceId\":" + invoice + ",\"amount\":\"10\"}]").andExpect(status().isOk());

        cancel(invoice, "Erreur").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVOICE_HAS_SETTLEMENTS"))
                .andExpect(jsonPath("$.context.blockedBy").value("credit_note"))
                .andExpect(jsonPath("$.context.documentId").value(note));
        cancel(note, "Erreur").andExpect(status().isConflict())
                .andExpect(jsonPath("$.context.documentId").value(invoice));
        cancel(wrong, " ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));

        cancel(wrong, "Montant saisi deux fois").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.open").value("0.00"))
                .andExpect(jsonPath("$.cancelReason").value("Montant saisi deux fois"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());

        cancel(wrong, "Encore").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        record(invoice("FA-31", "40", null, null, false, null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
        assertThat(jdbc.queryForObject("SELECT new_values FROM audit_log WHERE action = 'SUPPLIER_INVOICE_CANCEL'"
                + " AND entity_id = ?", String.class, wrong)).contains("Montant saisi deux fois");
        apply(note, "[{\"invoiceId\":" + wrong + ",\"amount\":\"1\"}]").andExpect(status().isBadRequest());
    }

    private ResultActions record(String body) throws Exception {
        return accountant.post("/api/supplier-invoices", body);
    }

    private ResultActions apply(long creditNote, String allocations) throws Exception {
        var request = MockMvcRequestBuilders.post("/api/supplier-invoices/" + creditNote + "/applications")
                .contentType(MediaType.APPLICATION_JSON).content("{\"allocations\":" + allocations + "}");
        return accountant.perform(request, true);
    }

    private ResultActions cancel(long id, String reason) throws Exception {
        return accountant.post("/api/supplier-invoices/" + id + "/cancel", "{\"reason\":\"" + reason + "\"}");
    }

    private void assertField(String body, String field, String code) throws Exception {
        record(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code));
    }

    private String invoice(String number, String total, Long receipt, Long returned, boolean creditNote, String due) {
        return "{\"supplierId\":" + supplier + ",\"invoiceNumber\":\"" + number + "\",\"invoiceDate\":\"2026-10-01\","
                + "\"totalTtc\":\"" + total + "\",\"creditNote\":" + creditNote
                + (receipt == null ? "" : ",\"receiptId\":" + receipt)
                + (returned == null ? "" : ",\"supplierReturnId\":" + returned)
                + (due == null ? "" : ",\"dueDate\":\"" + due + "\"") + "}";
    }

    private long supplier(String name, int terms) {
        String unique = name + " " + System.nanoTime();
        jdbc.update("""
                INSERT INTO suppliers (name, payment_terms_days, created_at, updated_at)
                VALUES (?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", unique, terms);
        return jdbc.queryForObject("SELECT id FROM suppliers WHERE name = ?", Long.class, unique);
    }

    private long receipt(long from, String lot) throws Exception {
        return id(admin.post("/api/goods-receipts", "{\"supplierId\":" + from + ",\"lines\":[{\"productId\":" + product
                + ",\"quantity\":\"10\",\"lotNumber\":\"" + lot + "-" + System.nanoTime() % 100000
                + "\",\"expiryDate\":\"2028-06-30\",\"unitCost\":\"2.5\",\"conforming\":true}]}")
                .andExpect(status().isCreated()));
    }

    private long supplierReturn(long to) {
        long user = new TestLots(jdbc).fixtureUser();
        String number = "SR-T-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO supplier_returns (number, supplier_id, reason, created_by, created_at)
                VALUES (?, ?, 'Périmés', ?, UTC_TIMESTAMP(6))""", number, to, user);
        return jdbc.queryForObject("SELECT id FROM supplier_returns WHERE number = ?", Long.class, number);
    }

    private static long id(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
