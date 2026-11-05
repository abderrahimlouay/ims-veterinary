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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
 * BR-RPT-006, ADR-148 item 6: the cash report lists the sessions closed in the period with their variance, shortages
 * and surpluses apart. BR-RPT-005: lot traceability gives every movement of a lot with its document and party, the
 * suppliers upstream and the customers downstream, anonymous counter sales included.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CashAndTraceabilityReportsTest {

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

    @PersistenceContext
    private EntityManager entities;

    private ApiClient admin;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        today = calendar.today();
        TestUser user = new TestUsers(jdbc, encoder).create("Administrator");
        admin = ApiClient.create(context, "198.51.100.247");
        admin.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void cashSessionsClosedInThePeriodWithShortagesAndSurplusesApart() throws Exception {
        TestUser user = new TestUsers(jdbc, encoder).create("Accountant");
        ApiClient cashier = ApiClient.create(context, "198.51.100.248");
        cashier.login(user.username(), user.password()).andExpect(status().isOk());
        long shortage = session(cashier, "100", "90");
        long surplus = session(cashier, "50", "55");
        session(cashier, "20", "20");
        cashier.post("/api/cash-sessions/open", "{\"openingAmount\":\"10\"}").andExpect(status().isCreated());

        admin.get("/api/reports/cash-sessions?cashierId=" + user.id()).andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(today.withDayOfMonth(1).toString()))
                .andExpect(jsonPath("$.to").value(today.toString()))
                .andExpect(jsonPath("$.cashierId").value(user.id()))
                .andExpect(jsonPath("$.rows.length()").value(3))
                .andExpect(jsonPath("$.rows[0].sessionId").value(shortage))
                .andExpect(jsonPath("$.rows[0].cashierName").value("Test User"))
                .andExpect(jsonPath("$.rows[0].closedByName").value("Test User"))
                .andExpect(jsonPath("$.rows[0].openingAmount").value("100.00"))
                .andExpect(jsonPath("$.rows[0].expectedAmount").value("100.00"))
                .andExpect(jsonPath("$.rows[0].countedAmount").value("90.00"))
                .andExpect(jsonPath("$.rows[0].variance").value("-10.00"))
                .andExpect(jsonPath("$.rows[1].sessionId").value(surplus))
                .andExpect(jsonPath("$.rows[1].variance").value("5.00"))
                .andExpect(jsonPath("$.sessions").value(3))
                .andExpect(jsonPath("$.sessionsWithVariance").value(2))
                .andExpect(jsonPath("$.totalShortage").value("-10.00"))
                .andExpect(jsonPath("$.totalSurplus").value("5.00"))
                .andExpect(jsonPath("$.totalVariance").value("-5.00"));
        admin.get("/api/reports/cash-sessions?cashierId=" + user.id() + "&from=" + today.minusMonths(2) + "&to="
                + today.minusMonths(1)).andExpect(status().isOk()).andExpect(jsonPath("$.rows").isEmpty())
                .andExpect(jsonPath("$.totalVariance").value("0.00"));
        admin.get("/api/reports/cash-sessions?from=" + today.plusDays(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("from"));
    }

    @Test
    void aLotIsTracedToItsSupplierAndEveryCustomer() throws Exception {
        long supplier = supplier("Labo Traçabilité");
        long product = new TestLots(jdbc).product("TRC-1", "Produit traçable");
        jdbc.update("UPDATE products SET sale_price = 100 WHERE id = ?", product);
        admin.post("/api/goods-receipts", "{\"supplierId\":" + supplier + ",\"lines\":[{\"productId\":" + product
                + ",\"quantity\":\"10\",\"lotNumber\":\"LOT-TRC\",\"expiryDate\":\"" + today.plusYears(1)
                + "\",\"unitCost\":\"1\",\"conforming\":true}]}").andExpect(status().isCreated());
        long lot = jdbc.queryForObject("SELECT id FROM lots WHERE lot_number = 'LOT-TRC'", Long.class);
        admin.post("/api/returns/supplier", "{\"supplierId\":" + supplier + ",\"reason\":\"Boîte abîmée\",\"lines\":"
                + "[{\"lotId\":" + lot + ",\"quantity\":\"1\"}]}").andExpect(status().isCreated());
        jdbc.update("""
                INSERT INTO customers (name, credit_limit, created_at, updated_at)
                VALUES ('Ferme Traçabilité', NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""");
        long customer = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        entities.clear();

        long invoice = sale("{\"docType\":\"invoice\",\"customerId\":" + customer + ",\"creditSale\":true}",
                product, "3", "{}");
        long saleLine = jdbc.queryForObject("SELECT id FROM sales_document_lines WHERE document_id = ?", Long.class,
                invoice);
        sale("{\"docType\":\"receipt\"}", product, "2",
                "{\"payments\":[{\"method\":\"cheque\",\"amount\":\"218.00\",\"reference\":\"CHQ-TRC\"}]}");
        admin.post("/api/returns/customer", "{\"documentId\":" + invoice + ",\"reason\":\"Retour\",\"lines\":"
                + "[{\"saleLineId\":" + saleLine + ",\"quantity\":\"1\",\"sealed\":true,\"disposition\":\"restock\"}]}")
                .andExpect(status().isCreated());

        admin.get("/api/reports/lot-traceability?lotId=" + lot).andExpect(status().isOk())
                .andExpect(jsonPath("$.lot.lotNumber").value("LOT-TRC"))
                .andExpect(jsonPath("$.lot.status").value("active"))
                .andExpect(jsonPath("$.lot.expired").value(false))
                .andExpect(jsonPath("$.lot.quantityOnHand").value("5.000"))
                .andExpect(jsonPath("$.lot.product.sku").value("TRC-1"))
                .andExpect(jsonPath("$.movements.length()").value(5))
                .andExpect(jsonPath("$.movements[0].type").value("purchase_receipt"))
                .andExpect(jsonPath("$.movements[0].document.type").value("goods_receipt"))
                .andExpect(jsonPath("$.movements[0].party.type").value("supplier"))
                .andExpect(jsonPath("$.movements[0].party.name").value("Labo Traçabilité"))
                .andExpect(jsonPath("$.movements[2].type").value("sale"))
                .andExpect(jsonPath("$.movements[2].document.type").value("invoice"))
                .andExpect(jsonPath("$.movements[2].party.name").value("Ferme Traçabilité"))
                .andExpect(jsonPath("$.movements[3].document.type").value("receipt"))
                .andExpect(jsonPath("$.movements[3].party").isEmpty())
                .andExpect(jsonPath("$.movements[4].type").value("customer_return"))
                .andExpect(jsonPath("$.movements[4].document.type").value("customer_return"))
                .andExpect(jsonPath("$.movements[4].document.number").isNotEmpty())
                .andExpect(jsonPath("$.movements[4].party.id").value(customer))
                .andExpect(jsonPath("$.suppliers.length()").value(1))
                .andExpect(jsonPath("$.suppliers[0].received").value("10.000"))
                .andExpect(jsonPath("$.suppliers[0].returned").value("1.000"))
                .andExpect(jsonPath("$.customers.length()").value(2))
                .andExpect(jsonPath("$.customers[0].id").value(customer))
                .andExpect(jsonPath("$.customers[0].sold").value("3.000"))
                .andExpect(jsonPath("$.customers[0].returned").value("1.000"))
                .andExpect(jsonPath("$.customers[0].net").value("2.000"))
                .andExpect(jsonPath("$.customers[1].id").isEmpty())
                .andExpect(jsonPath("$.customers[1].sold").value("2.000"));
        admin.get("/api/reports/lot-traceability?lotId=999999").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void theseReportsNeedTheirPermission() throws Exception {
        TestUser seller = new TestUsers(jdbc, encoder).create("Seller");
        ApiClient client = ApiClient.create(context, "198.51.100.249");
        client.login(seller.username(), seller.password()).andExpect(status().isOk());
        client.get("/api/reports/cash-sessions").andExpect(status().isForbidden());
        client.get("/api/reports/lot-traceability?lotId=1").andExpect(status().isForbidden());
    }

    @Test
    void cashAndTraceabilityExportAsCsv() throws Exception {
        TestUser user = new TestUsers(jdbc, encoder).create("Accountant");
        ApiClient cashier = ApiClient.create(context, "198.51.100.252");
        cashier.login(user.username(), user.password()).andExpect(status().isOk());
        long id = session(cashier, "40", "38");
        assertThat(csv(admin.get("/api/reports/cash-sessions?format=csv&cashierId=" + user.id())))
                .startsWith("Session;Caissier;Ouverture;Clôture;Clôturée par;Fonds initial;Attendu;Compté;Écart\r\n"
                        + id + ";Test User;")
                .contains(";Test User;40,00;40,00;38,00;-2,00\r\n")
                .endsWith("Écart net;;;;;;;;-2,00\r\n");

        long product = new TestLots(jdbc).product("TRC-CSV", "Produit export");
        long lot = new TestLots(jdbc).lot(product, "LOT-CSV", today.plusYears(1), "4");
        assertThat(csv(admin.get("/api/reports/lot-traceability?format=csv&lotId=" + lot)))
                .startsWith("Lot;LOT-CSV;Produit;TRC-CSV;Produit export;Péremption;")
                .contains("\r\n01/10/2026 09:00;Stock initial;4,000;;;;Lot fixture\r\n");
    }

    private long session(ApiClient cashier, String opening, String counted) throws Exception {
        long id = id(cashier.post("/api/cash-sessions/open", "{\"openingAmount\":\"" + opening + "\"}")
                .andExpect(status().isCreated()));
        cashier.post("/api/cash-sessions/" + id + "/close", "{\"countedAmount\":\"" + counted + "\"}")
                .andExpect(status().isOk());
        return id;
    }

    private long sale(String header, long product, String quantity, String confirmation) throws Exception {
        long id = id(admin.post("/api/sales", header).andExpect(status().isCreated()));
        admin.post("/api/sales/" + id + "/lines", "{\"productId\":" + product + ",\"quantity\":\"" + quantity + "\"}")
                .andExpect(status().isOk());
        admin.post("/api/sales/" + id + "/confirm", confirmation).andExpect(status().isOk());
        return id;
    }

    private long supplier(String name) {
        jdbc.update("INSERT INTO suppliers (name, created_at, updated_at) VALUES (?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                name);
        return jdbc.queryForObject("SELECT id FROM suppliers WHERE name = ?", Long.class, name);
    }

    private static long id(ResultActions result) throws Exception {
        JsonNode node = JSON.readTree(result.andReturn().getResponse().getContentAsString());
        return node.get("id").asLong();
    }

    private static String csv(ResultActions result) throws Exception {
        byte[] bytes = result.andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", startsWith("attachment; filename=")))
                .andReturn().getResponse().getContentAsByteArray();
        return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    }
}
