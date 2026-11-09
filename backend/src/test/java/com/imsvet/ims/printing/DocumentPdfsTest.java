package com.imsvet.ims.printing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.printing.application.PrintFormats;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
 * The credit note of a customer return, a purchase order, a recall buyers list, a customer statement and a cash-session
 * report as PDFs (ADR-137, ADR-143), checked on their extracted text; a draft order refused; the JSON views unchanged
 * without {@code format=pdf}; another cashier's session report refused.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class DocumentPdfsTest {

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
    private long lot;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        TestLots fixtures = new TestLots(jdbc);
        TestUser user = new TestUsers(jdbc, encoder).create("Administrator");
        admin = ApiClient.create(context, "198.51.100.241");
        admin.login(user.username(), user.password()).andExpect(status().isOk());
        product = fixtures.product("PDF-DOC-1", "Ivomec Démo");
        jdbc.update("UPDATE products SET sale_price = 1000 WHERE id = ?", product);
        lot = fixtures.lot(product, "LOT-RAPPEL-1", calendar.today().plusYears(1), "50");
        entities.clear();
    }

    @Test
    void theCreditNoteOfAReturnPrintsWithItsInvoiceAndAmountInWords() throws Exception {
        long customer = creditCustomer("Élevage Démo Avoir");
        JsonNode invoice = creditInvoice(customer, "2");
        long saleLine = invoice.get("lines").get(0).get("id").asLong();
        long returned = id(admin.post("/api/returns/customer", "{\"documentId\":" + invoice.get("id").asLong()
                + ",\"reason\":\"Flacon en trop\",\"lines\":[{\"saleLineId\":" + saleLine
                + ",\"quantity\":\"1\",\"sealed\":true,\"disposition\":\"restock\"}]}").andExpect(status().isCreated()));

        String text = text(admin.get("/api/returns/customer/" + returned + "/pdf").andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf")));
        assertThat(text).contains("AVOIR").contains("AV-").contains("Avoir sur " + invoice.get("number").asString())
                .contains("Élevage Démo Avoir").contains("1 090,00 DA")
                .contains("Arrêté le présent avoir à la somme de : Mille quatre-vingt-dix dinars algériens.")
                .doesNotContain("Délais d'attente");
        admin.get("/api/returns/customer/999999/pdf").andExpect(status().isNotFound());
    }

    @Test
    void aPurchaseOrderPrintsOnceValidatedAndCarriesItsCancellation() throws Exception {
        long supplier = id(admin.post("/api/suppliers", "{\"name\":\"Grossiste Démo PDF\",\"address\":\"Zone industrielle, Blida\","
                + "\"taxId\":\"NIF-FOURN-1\"}").andExpect(status().isCreated()));
        long order = id(admin.post("/api/purchase-orders", "{\"supplierId\":" + supplier + ",\"lines\":[{\"productId\":"
                + product + ",\"quantity\":\"10\",\"unitPrice\":\"5\"}]}").andExpect(status().isCreated()));
        admin.get("/api/purchase-orders/" + order + "/pdf").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        admin.post("/api/purchase-orders/" + order + "/validate", "").andExpect(status().isOk());

        String text = text(admin.get("/api/purchase-orders/" + order + "/pdf").andExpect(status().isOk()));
        assertThat(text).contains("BON DE COMMANDE").contains("BC-").contains("Grossiste Démo PDF")
                .contains("Zone industrielle, Blida").contains("NIF NIF-FOURN-1").contains("PDF-DOC-1")
                .contains("Ivomec Démo").contains("50,00").doesNotContain("ANNULÉ");

        admin.post("/api/purchase-orders/" + order + "/cancel", "{\"reason\":\"Commande en double\"}")
                .andExpect(status().isOk());
        assertThat(text(admin.get("/api/purchase-orders/" + order + "/pdf").andExpect(status().isOk())))
                .contains("ANNULÉ").contains("Commande en double");
    }

    @Test
    void theRecallListNamesTheBuyersWithWhatTheyStillHold() throws Exception {
        long customer = creditCustomer("Ferme Démo Rappel");
        JsonNode invoice = creditInvoice(customer, "3");
        admin.post("/api/lots/" + lot + "/recall", "{\"reason\":\"Avis du fabricant n° 7\"}").andExpect(status().isOk());

        admin.get("/api/lots/" + lot + "/recall-report").andExpect(status().isOk())
                .andExpect(jsonPath("$.buyers[0].customerName").value("Ferme Démo Rappel"));
        String text = text(admin.get("/api/lots/" + lot + "/recall-report?format=pdf").andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf")));
        assertThat(text).contains("RAPPEL DE LOT").contains("Ivomec Démo (PDF-DOC-1)").contains("LOT-RAPPEL-1")
                .contains("Motif : Avis du fabricant n° 7").contains("Ferme Démo Rappel")
                .contains(invoice.get("number").asString()).contains("47");
    }

    @Test
    void theStatementPrintsTheCustomerTheEntriesAndTheClosingBalance() throws Exception {
        long customer = creditCustomer("Élevage Démo Relevé");
        jdbc.update("UPDATE customers SET nis = 'NIS-REL-1' WHERE id = ?", customer);
        JsonNode invoice = creditInvoice(customer, "2");
        admin.post("/api/payments/customers", "{\"customerId\":" + customer + ",\"method\":\"cheque\","
                + "\"amount\":\"500\",\"reference\":\"CHQ-77\"}").andExpect(status().isCreated());

        admin.get("/api/customers/" + customer + "/statement").andExpect(status().isOk())
                .andExpect(jsonPath("$.closingBalance").value("1680.00"));
        String text = text(admin.get("/api/customers/" + customer + "/statement?format=pdf&from="
                + calendar.today()).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf")));
        assertThat(text).contains("RELEVÉ DE COMPTE").contains("Du " + PrintFormats.date(calendar.today()))
                .contains("Élevage Démo Relevé").contains("NIS NIS-REL-1").contains("Solde d'ouverture")
                .contains("Facture").contains(invoice.get("number").asString()).contains("2 180,00")
                .contains("Règlement").contains("Chèque CHQ-77").contains("500,00").contains("1 680,00 DA");
        admin.get("/api/customers/" + customer + "/statement?format=pdf&from=" + calendar.today().plusDays(1)
                + "&to=" + calendar.today()).andExpect(status().isBadRequest());
        admin.get("/api/customers/999999/statement?format=pdf").andExpect(status().isNotFound());
    }

    @Test
    void theCashReportPrintsTheSessionByOriginAndOnlyForItsReaders() throws Exception {
        long session = id(admin.post("/api/cash-sessions/open", "{\"openingAmount\":\"100\"}")
                .andExpect(status().isCreated()));
        admin.post("/api/cash-sessions/" + session + "/movements", "{\"direction\":\"in\",\"amount\":\"40\","
                + "\"label\":\"Apport\"}").andExpect(status().isCreated());

        String text = text(admin.get("/api/cash-sessions/" + session + "/report?format=pdf").andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf")));
        assertThat(text).contains("RAPPORT DE CAISSE").contains("Session n° " + session)
                .contains("Session ouverte").contains("Fond de caisse : 100,00 DA").contains("Mouvements manuels")
                .contains("40,00").contains("Attendu : 140,00 DA").contains("Compté : —");

        TestUser sellerUser = new TestUsers(jdbc, encoder).create("Seller");
        ApiClient seller = ApiClient.create(context, "198.51.100.242");
        seller.login(sellerUser.username(), sellerUser.password()).andExpect(status().isOk());
        seller.get("/api/cash-sessions/" + session + "/report?format=pdf").andExpect(status().isForbidden());
    }

    private long creditCustomer(String name) throws Exception {
        long customer = id(admin.post("/api/customers", "{\"name\":\"" + name + "\"}").andExpect(status().isCreated()));
        jdbc.update("UPDATE customers SET credit_limit = NULL WHERE id = ?", customer);
        return customer;
    }

    private JsonNode creditInvoice(long customer, String quantity) throws Exception {
        long id = id(admin.post("/api/sales", "{\"docType\":\"invoice\",\"customerId\":" + customer
                + ",\"creditSale\":true}").andExpect(status().isCreated()));
        admin.post("/api/sales/" + id + "/lines", "{\"productId\":" + product + ",\"quantity\":\"" + quantity + "\"}")
                .andExpect(status().isOk());
        return JSON.readTree(admin.post("/api/sales/" + id + "/confirm", "{}").andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }

    private static String text(ResultActions result) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(result.andReturn().getResponse().getContentAsByteArray())) {
            return new PDFTextStripper().getText(pdf).replace(' ', ' ');
        }
    }

    private static long id(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
