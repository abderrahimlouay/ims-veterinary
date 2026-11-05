package com.imsvet.ims.reporting;

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
 * BR-RPT-001, BR-RPT-003, ADR-147: revenue and margin count confirmed invoices and receipts net of credit notes; a
 * delivery note through its invoice only, the uninvoiced ones apart; quotes and cancelled documents never; margin
 * needs {@code report.margin}. FR-RPT-003: statistics by period, customer and zone, for one customer type.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SalesReportsTest {

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
    private long product;
    private long customer;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        today = calendar.today();
        TestLots fixtures = new TestLots(jdbc);
        TestUser user = new TestUsers(jdbc, encoder).create("Administrator");
        admin = ApiClient.create(context, "198.51.100.243");
        admin.login(user.username(), user.password()).andExpect(status().isOk());
        jdbc.update("INSERT INTO therapeutic_categories (code, name) VALUES ('RPT-CAT', 'Catégorie rapport')");
        int category = jdbc.queryForObject("SELECT id FROM therapeutic_categories WHERE code = 'RPT-CAT'", Integer.class);
        product = fixtures.product("RPT-1", "Produit rapport");
        jdbc.update("UPDATE products SET sale_price = 1000, category_id = ? WHERE id = ?", category, product);
        long other = fixtures.product("RPT-2", "Produit sans catégorie");
        jdbc.update("UPDATE products SET sale_price = 100 WHERE id = ?", other);
        fixtures.lot(product, "LOT-RPT-1", today.plusYears(1), "100");
        fixtures.lot(other, "LOT-RPT-2", today.plusYears(1), "100");
        customer = id(admin.post("/api/customers", "{\"name\":\"Ferme Démo Rapport\",\"wilaya\":\"Djelfa\"}")
                .andExpect(status().isCreated()));
        jdbc.update("UPDATE customers SET credit_limit = NULL WHERE id = ?", customer);
        entities.clear();

        JsonNode invoice = confirmed(draft("invoice", product, "2"));                 // 2 000 HT, cost 1.00
        long saleLine = invoice.get("lines").get(0).get("id").asLong();
        admin.post("/api/returns/customer", "{\"documentId\":" + invoice.get("id").asLong()
                + ",\"reason\":\"Retour\",\"lines\":[{\"saleLineId\":" + saleLine
                + ",\"quantity\":\"1\",\"sealed\":true,\"disposition\":\"restock\"}]}")
                .andExpect(status().isCreated());                                      // -1 000 HT, cost -0.50
        confirmed(draft("invoice", other, "3"));                                       // 300 HT, cost 1.50
        long note = id(confirmedRaw(draft("delivery_note", product, "1")));            // through its invoice
        long noteInvoice = id(admin.post("/api/sales/" + note + "/convert", "{\"targetType\":\"invoice\"}")
                .andExpect(status().isCreated()));
        confirmed(noteInvoice);                                                         // 1 000 HT, cost 0.50
        confirmedRaw(draft("delivery_note", product, "4"));                            // uninvoiced: 4 000 HT
        draft("quote", product, "5");
        admin.post("/api/sales/" + confirmed(draft("invoice", product, "7")).get("id").asLong() + "/cancel",
                "{\"reason\":\"Erreur de saisie\"}").andExpect(status().isOk());
    }

    @Test
    void revenueIsNetOfCreditNotesWithUninvoicedDeliveryNotesApart() throws Exception {
        admin.get("/api/reports/revenue?groupBy=day").andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(today.withDayOfMonth(1).toString()))
                .andExpect(jsonPath("$.to").value(today.toString()))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].key").value(today.toString()))
                .andExpect(jsonPath("$.rows[0].revenueHt").value("2300.00"))
                .andExpect(jsonPath("$.totalHt").value("2300.00"))
                .andExpect(jsonPath("$.uninvoicedDeliveryNotes.count").value(1))
                .andExpect(jsonPath("$.uninvoicedDeliveryNotes.totalHt").value("4000.00"));
        admin.get("/api/reports/revenue?groupBy=category").andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].label").value("Catégorie rapport"))
                .andExpect(jsonPath("$.rows[0].revenueHt").value("2000.00"))
                .andExpect(jsonPath("$.rows[1].key").isEmpty())
                .andExpect(jsonPath("$.rows[1].revenueHt").value("300.00"));
        admin.get("/api/reports/revenue?groupBy=month").andExpect(jsonPath("$.rows[0].key")
                .value(today.toString().substring(0, 7)));
    }

    @Test
    void marginTakesBackTheCostOfWhatACreditNoteCredits() throws Exception {
        admin.get("/api/reports/margin?groupBy=product").andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].code").value("RPT-1"))
                .andExpect(jsonPath("$.rows[0].quantityBase").value("2.000"))
                .andExpect(jsonPath("$.rows[0].revenueHt").value("2000.00"))
                .andExpect(jsonPath("$.rows[0].cost").value("1.00"))
                .andExpect(jsonPath("$.rows[0].margin").value("1999.00"))
                .andExpect(jsonPath("$.rows[0].marginRate").value("99.95"))
                .andExpect(jsonPath("$.rows[1].code").value("RPT-2"))
                .andExpect(jsonPath("$.rows[1].cost").value("1.50"))
                .andExpect(jsonPath("$.totalRevenueHt").value("2300.00"))
                .andExpect(jsonPath("$.totalCost").value("2.50"))
                .andExpect(jsonPath("$.totalMargin").value("2297.50"));
    }

    @Test
    void statisticsCountInvoicesAndReceiptsByCustomerAndZone() throws Exception {
        admin.get("/api/reports/sales-statistics?groupBy=customer").andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].key").value(String.valueOf(customer)))
                .andExpect(jsonPath("$.rows[0].label").value("Ferme Démo Rapport"))
                .andExpect(jsonPath("$.rows[0].documents").value(3))
                .andExpect(jsonPath("$.rows[0].revenueHt").value("2300.00"))
                .andExpect(jsonPath("$.totalDocuments").value(3));
        admin.get("/api/reports/sales-statistics?groupBy=wilaya&customerType=breeder").andExpect(status().isOk())
                .andExpect(jsonPath("$.customerType").value("breeder"))
                .andExpect(jsonPath("$.rows[0].label").value("Djelfa"))
                .andExpect(jsonPath("$.rows[0].revenueHt").value("2300.00"));
        admin.get("/api/reports/sales-statistics?groupBy=day").andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].documents").value(3));
        admin.get("/api/reports/sales-statistics?customerType=veterinarian").andExpect(status().isOk())
                .andExpect(jsonPath("$.rows").isEmpty()).andExpect(jsonPath("$.totalHt").value("0.00"));
        admin.get("/api/reports/sales-statistics?customerType=farmer").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void periodsAndGroupingsAreValidated() throws Exception {
        admin.get("/api/reports/revenue?from=" + today.plusDays(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        admin.get("/api/reports/margin?groupBy=day").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        admin.get("/api/reports/revenue?from=" + today.minusYears(1) + "&to=" + today.minusMonths(6))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows").isEmpty())
                .andExpect(jsonPath("$.totalHt").value("0.00"));
    }

    @Test
    void marginNeedsItsOwnPermission() throws Exception {
        TestUser seller = new TestUsers(jdbc, encoder).create("Seller");
        ApiClient client = ApiClient.create(context, "198.51.100.244");
        client.login(seller.username(), seller.password()).andExpect(status().isOk());
        client.get("/api/reports/margin").andExpect(status().isForbidden());
    }

    private long draft(String type, long productId, String quantity) throws Exception {
        long id = id(admin.post("/api/sales", "{\"docType\":\"" + type + "\",\"customerId\":" + customer
                + ",\"creditSale\":" + !"quote".equals(type) + "}").andExpect(status().isCreated()));
        admin.post("/api/sales/" + id + "/lines", "{\"productId\":" + productId + ",\"quantity\":\"" + quantity
                + "\"}").andExpect(status().isOk());
        return id;
    }

    private ResultActions confirmedRaw(long id) throws Exception {
        return admin.post("/api/sales/" + id + "/confirm", "{}").andExpect(status().isOk());
    }

    private JsonNode confirmed(long id) throws Exception {
        return JSON.readTree(confirmedRaw(id).andReturn().getResponse().getContentAsString());
    }

    private static long id(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
