package com.imsvet.ims.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * BR-RPT-004, ADR-148: the receivables ageing ages each credit invoice's open amount by days past its due date into the
 * {@code ageing_buckets} columns, keeps advances and uninvoiced credit delivery notes apart, and gives the ADR-118
 * balance of the Account tab; the payables list open supplier invoices by due date and give the BR-PAY-014 balance.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class FinanceReportsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    private ApiClient accountant;
    private long operator;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        today = calendar.today();
        operator = new TestLots(jdbc).fixtureUser();
        TestUser user = new TestUsers(jdbc, encoder).create("Accountant");
        accountant = ApiClient.create(context, "198.51.100.245");
        accountant.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void receivablesAreAgedByDaysPastDueAndMatchTheAccountBalance() throws Exception {
        long breeder = customer("Ferme Ancienneté", "breeder");
        document("invoice", "AGE-NOT-DUE", breeder, null, true, today.plusDays(10), "100.00");
        long late = document("invoice", "AGE-5", breeder, null, true, today.minusDays(5), "200.00");
        document("credit_note", "AGE-5-CN", breeder, late, true, null, "50.00");
        long older = document("invoice", "AGE-45", breeder, null, true, today.minusDays(45), "300.00");
        document("invoice", "AGE-100", breeder, null, true, today.minusDays(100), "80.00");
        long cleared = document("invoice", "AGE-CLEARED", breeder, null, true, today.minusDays(70), "60.00");
        document("credit_note", "AGE-CLEARED-CN", breeder, cleared, true, null, "60.00");
        document("delivery_note", "AGE-BL", breeder, null, true, null, "40.00");
        document("receipt", "AGE-CASH", breeder, null, false, null, "999.00");
        pay(breeder, "150", "[{\"invoiceId\":" + older + ",\"amount\":\"100\"}]");

        long vet = customer("Cabinet Ancienneté", "veterinarian");
        long vetInvoice = document("invoice", "AGE-31", vet, null, true, today.minusDays(31), "70.00");
        long reversed = pay(vet, "70", "[{\"invoiceId\":" + vetInvoice + ",\"amount\":\"70\"}]");
        accountant.post("/api/payments/customers/" + reversed + "/reverse", "{\"reason\":\"Chèque impayé\"}")
                .andExpect(status().isOk());

        String row = "$.rows[?(@.customerId == " + breeder + ")]";
        accountant.get("/api/reports/customer-balances").andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(today.toString()))
                .andExpect(jsonPath("$.bucketDays[0]").value(30))
                .andExpect(jsonPath("$.bucketDays[2]").value(90))
                .andExpect(jsonPath(row + ".name").value("Ferme Ancienneté"))
                .andExpect(jsonPath(row + ".notDue").value("100.00"))
                .andExpect(jsonPath(row + ".overdue[0]").value("150.00"))
                .andExpect(jsonPath(row + ".overdue[1]").value("200.00"))
                .andExpect(jsonPath(row + ".overdue[2]").value("0.00"))
                .andExpect(jsonPath(row + ".overdue[3]").value("80.00"))
                .andExpect(jsonPath(row + ".advance").value("50.00"))
                .andExpect(jsonPath(row + ".balance").value("480.00"))
                .andExpect(jsonPath(row + ".uninvoicedDeliveryNotes").value("40.00"))
                .andExpect(jsonPath("$.rows[?(@.customerId == " + vet + ")].overdue[1]").value("70.00"))
                .andExpect(jsonPath("$.rows[?(@.customerId == " + vet + ")].balance").value("70.00"));
        accountant.get("/api/customers/" + breeder + "/balance").andExpect(jsonPath("$.balance").value("480.00"))
                .andExpect(jsonPath("$.uninvoicedDeliveryNotes").value("40.00"));
        accountant.get("/api/customers/" + vet + "/balance").andExpect(jsonPath("$.balance").value("70.00"));

        JsonNode report = read(accountant.get("/api/reports/customer-balances").andExpect(status().isOk()));
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode r : report.get("rows")) {
            sum = sum.add(new BigDecimal(r.get("balance").asString()));
        }
        assertThat(new BigDecimal(report.get("totals").get("balance").asString())).isEqualByComparingTo(sum);

        accountant.get("/api/reports/customer-balances?customerType=veterinarian").andExpect(status().isOk())
                .andExpect(jsonPath("$.customerType").value("veterinarian"))
                .andExpect(jsonPath("$.rows[?(@.customerId == " + breeder + ")]").isEmpty());
        accountant.get("/api/reports/customer-balances?customerType=farmer").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("customerType"))
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN"));
    }

    @Test
    void theColumnsFollowTheAgeingBucketsSetting() throws Exception {
        long breeder = customer("Ferme Colonnes", "breeder");
        document("invoice", "COL-5", breeder, null, true, today.minusDays(5), "10.00");
        document("invoice", "COL-20", breeder, null, true, today.minusDays(20), "20.00");
        document("invoice", "COL-45", breeder, null, true, today.minusDays(45), "30.00");
        document("invoice", "COL-61", breeder, null, true, today.minusDays(61), "40.00");
        jdbc.update("UPDATE app_settings SET setting_value = '15,30,60' WHERE setting_key = 'ageing_buckets'");

        String row = "$.rows[?(@.customerId == " + breeder + ")]";
        accountant.get("/api/reports/customer-balances").andExpect(status().isOk())
                .andExpect(jsonPath("$.bucketDays[0]").value(15))
                .andExpect(jsonPath(row + ".overdue[0]").value("10.00"))
                .andExpect(jsonPath(row + ".overdue[1]").value("20.00"))
                .andExpect(jsonPath(row + ".overdue[2]").value("30.00"))
                .andExpect(jsonPath(row + ".overdue[3]").value("40.00"));
    }

    @Test
    void payablesListOpenInvoicesByDueDateAndMatchTheSupplierBalance() throws Exception {
        long supplier = supplier("Labo Échéances");
        long old = supplierInvoice(supplier, "FA-OLD", today.minusDays(40), today.minusDays(40), "500", false);
        supplierInvoice(supplier, "FA-NEW", today.minusDays(2), today.plusDays(28), "120", false);
        long paid = supplierInvoice(supplier, "FA-PAID", today.minusDays(60), today.minusDays(60), "90", false);
        supplierInvoice(supplier, "AV-1", today.minusDays(1), today.minusDays(1), "50", true);
        long cancelled = supplierInvoice(supplier, "FA-ERR", today.minusDays(3), today.minusDays(3), "999", false);
        accountant.post("/api/supplier-invoices/" + cancelled + "/cancel", "{\"reason\":\"Erreur\"}")
                .andExpect(status().isOk());
        paySupplier(supplier, "200", "[{\"invoiceId\":" + old + ",\"amount\":\"200\"}]");
        paySupplier(supplier, "90", "[{\"invoiceId\":" + paid + ",\"amount\":\"90\"}]");
        paySupplier(supplier, "30", "[]");

        accountant.get("/api/reports/supplier-payables?supplierId=" + supplier).andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierId").value(supplier))
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].invoiceNumber").value("FA-OLD"))
                .andExpect(jsonPath("$.rows[0].supplierName").value(startsWith("Labo Échéances")))
                .andExpect(jsonPath("$.rows[0].daysOverdue").value(40))
                .andExpect(jsonPath("$.rows[0].totalTtc").value("500.00"))
                .andExpect(jsonPath("$.rows[0].open").value("300.00"))
                .andExpect(jsonPath("$.rows[1].invoiceNumber").value("FA-NEW"))
                .andExpect(jsonPath("$.rows[1].daysOverdue").value(0))
                .andExpect(jsonPath("$.totalOpen").value("420.00"))
                .andExpect(jsonPath("$.totalOverdue").value("300.00"))
                .andExpect(jsonPath("$.unappliedCreditNotes").value("50.00"))
                .andExpect(jsonPath("$.advances").value("30.00"))
                .andExpect(jsonPath("$.balance").value("340.00"));
        accountant.get("/api/suppliers/" + supplier + "/payables").andExpect(jsonPath("$.balance").value("340.00"));
    }

    @Test
    void financeReportsNeedTheirPermission() throws Exception {
        TestUser seller = new TestUsers(jdbc, encoder).create("Seller");
        ApiClient client = ApiClient.create(context, "198.51.100.246");
        client.login(seller.username(), seller.password()).andExpect(status().isOk());
        client.get("/api/reports/customer-balances").andExpect(status().isForbidden());
        client.get("/api/reports/supplier-payables").andExpect(status().isForbidden());
    }

    @Test
    void balancesAndPayablesExportAsCsv() throws Exception {
        long breeder = customer("Ferme Export", "breeder");
        document("invoice", "EXP-40", breeder, null, true, today.minusDays(40), "250.00");
        assertThat(csv(accountant.get("/api/reports/customer-balances?format=csv")))
                .startsWith("Client;Téléphone;Wilaya;Non échu;1–30 j;31–60 j;61–90 j;> 90 j;Avance;Solde;"
                        + "BL non facturés\r\n")
                .contains("\r\nFerme Export;;;0,00;0,00;250,00;0,00;0,00;0,00;250,00;0,00\r\n")
                .contains("\r\nTotal;;;");

        long supplier = supplier("Labo Export");
        supplierInvoice(supplier, "FA-EXP", today.minusDays(3), today.minusDays(3), "75", false);
        assertThat(csv(accountant.get("/api/reports/supplier-payables?format=csv&supplierId=" + supplier)))
                .contains(";FA-EXP;" + date(today.minusDays(3)) + ";" + date(today.minusDays(3)) + ";3;75,00;75,00\r\n")
                .endsWith("Solde;;;;;;75,00\r\n");
    }

    private static String date(LocalDate date) {
        return date.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    private long customer(String name, String type) {
        jdbc.update("""
                INSERT INTO customers (name, customer_type, credit_limit, created_at, updated_at)
                VALUES (?, ?, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", name, type);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
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

    private long pay(long customer, String amount, String allocations) throws Exception {
        return id(accountant.post("/api/payments/customers", "{\"customerId\":" + customer
                + ",\"method\":\"cheque\",\"amount\":\"" + amount + "\",\"reference\":\"CHQ-AGE\",\"allocations\":"
                + allocations + "}").andExpect(status().isCreated()));
    }

    private long supplier(String name) {
        String unique = name + " " + System.nanoTime();
        jdbc.update("INSERT INTO suppliers (name, created_at, updated_at) VALUES (?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                unique);
        return jdbc.queryForObject("SELECT id FROM suppliers WHERE name = ?", Long.class, unique);
    }

    private long supplierInvoice(long supplier, String number, LocalDate date, LocalDate due, String total,
            boolean creditNote) throws Exception {
        return id(accountant.post("/api/supplier-invoices", "{\"supplierId\":" + supplier + ",\"invoiceNumber\":\""
                + number + "\",\"invoiceDate\":\"" + date + "\",\"dueDate\":\"" + due + "\",\"totalTtc\":\"" + total
                + "\",\"creditNote\":" + creditNote + "}").andExpect(status().isCreated()));
    }

    private void paySupplier(long supplier, String amount, String allocations) throws Exception {
        accountant.post("/api/payments/suppliers", "{\"supplierId\":" + supplier + ",\"method\":\"bank_transfer\","
                + "\"amount\":\"" + amount + "\",\"reference\":\"VIR-ECH\",\"allocations\":" + allocations + "}")
                .andExpect(status().isCreated());
    }

    private static JsonNode read(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static long id(ResultActions result) throws Exception {
        return read(result).get("id").asLong();
    }

    private static String csv(ResultActions result) throws Exception {
        byte[] bytes = result.andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", startsWith("attachment; filename=")))
                .andReturn().getResponse().getContentAsByteArray();
        return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    }
}
