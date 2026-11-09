package com.imsvet.ims.printing;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sales documents as PDFs (ADR-138…143), checked on their extracted text: the identity in force at the issue, the
 * customer's legal numbers, VAT by rate, the amount in words, the withdrawal warnings for any customer, the 80 mm
 * ticket, "ANNULÉ" on a cancelled document, a draft refused, the read permission, under 2 s.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SalePdfsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final float MM = 72f / 25.4f;

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
    private ApiClient admin;
    private long product;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        LocalDate today = calendar.today();
        TestLots fixtures = new TestLots(jdbc);
        TestUser user = new TestUsers(jdbc, encoder).create("Seller");
        seller = ApiClient.create(context, "198.51.100.231");
        seller.login(user.username(), user.password()).andExpect(status().isOk());
        TestUser administrator = new TestUsers(jdbc, encoder).create("Administrator");
        admin = ApiClient.create(context, "198.51.100.232");
        admin.login(administrator.username(), administrator.password()).andExpect(status().isOk());
        product = fixtures.product("PDF-1", "Oxyvet Démo 20 %");
        jdbc.update("UPDATE products SET sale_price = 1000 WHERE id = ?", product);
        fixtures.lot(product, "LOT-PDF-1", today.plusYears(1), "50");
        jdbc.update("INSERT INTO product_withdrawal_periods (product_id, species_id, meat_days, milk_days)"
                + " SELECT ?, id, 28, 7 FROM animal_species WHERE code = 'BOVINE'", product);
        entities.clear();
    }

    @Test
    void anInvoicePrintsTheIdentityTheCustomerVatTheAmountInWordsAndTheWarnings() throws Exception {
        profile("Pharmacie Vétérinaire Démo", "000016001234567");
        long customer = id(seller.post("/api/customers", "{\"name\":\"مزرعة النخيل التجريبية\",\"wilaya\":\"Biskra\","
                + "\"taxId\":\"NIF-CLIENT-1\",\"rc\":\"RC-CLIENT-1\"}").andExpect(status().isCreated()));
        jdbc.update("UPDATE customers SET credit_limit = NULL WHERE id = ?", customer);
        long invoice = sale("{\"docType\":\"invoice\",\"customerId\":" + customer + ",\"creditSale\":true}", "2");
        seller.post("/api/sales/" + invoice + "/confirm", "{}").andExpect(status().isOk());

        long started = System.nanoTime();
        MvcResult result = seller.get("/api/sales/" + invoice + "/pdf").andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline;")))
                .andReturn();
        long millis = (System.nanoTime() - started) / 1_000_000;
        String text = text(result);

        assertThat(text).contains("FACTURE").contains("FAC-").contains("Pharmacie Vétérinaire Démo")
                .contains("NIF 000016001234567").contains("NIF NIF-CLIENT-1").contains("RC RC-CLIENT-1")
                .contains("Oxyvet Démo 20 %").contains("LOT-PDF-1").contains("Vente à crédit").contains("Échéance")
                .contains("9 %").contains("2 000,00").contains("2 180,00 DA")
                .contains("Arrêtée la présente facture à la somme de : Deux mille cent quatre-vingts dinars algériens.")
                .contains("Délais d'attente").contains("Oxyvet Démo 20 % — Bovin : viande 28 j, lait 7 j");
        assertThat(text).contains("مزرعة");
        assertThat(millis).as("rendering time").isLessThan(2000);
    }

    @Test
    void aReceiptPrintsAsAnEightyMillimetreTicketWithItsPaymentAndWarnings() throws Exception {
        seller.post("/api/cash-sessions/open", "{\"openingAmount\":\"0\"}").andExpect(status().isCreated());
        long receipt = sale("{\"docType\":\"receipt\"}", "1");
        seller.post("/api/sales/" + receipt + "/confirm",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":\"1090.00\"}]}").andExpect(status().isOk());

        MvcResult result = seller.get("/api/sales/" + receipt + "/pdf?format=ticket").andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("-ticket.pdf")))
                .andReturn();
        try (PDDocument pdf = Loader.loadPDF(result.getResponse().getContentAsByteArray())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
            PDRectangle page = pdf.getPage(0).getMediaBox();
            assertThat(page.getWidth() / MM).isCloseTo(80f, org.assertj.core.data.Offset.offset(0.5f));
            assertThat(page.getHeight() / MM).isBetween(40f, 300f);
        }
        String text = text(result);
        assertThat(text).contains("TICKET DE CAISSE").contains("Espèces : 1 090,00 DA").contains("TOTAL TTC")
                .contains("Bovin : viande 28 j").contains("Identité de l'entreprise non renseignée")
                .doesNotContain("Arrêtée");
    }

    @Test
    void aCancelledDocumentCarriesItsMarkAndADraftIsRefused() throws Exception {
        long note = sale("{\"docType\":\"quote\",\"customerId\":" + customer() + "}", "1");
        seller.get("/api/sales/" + note + "/pdf").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        seller.get("/api/sales/" + note + "/pdf?format=a5").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("format"));
        seller.post("/api/sales/" + note + "/confirm", "{}").andExpect(status().isOk());
        String quote = text(seller.get("/api/sales/" + note + "/pdf").andExpect(status().isOk()).andReturn());
        assertThat(quote).contains("DEVIS").contains("Bovin : viande 28 j").doesNotContain("Arrêtée");

        TestUser manager = new TestUsers(jdbc, encoder).create("Pharmacist");
        ApiClient pharmacist = ApiClient.create(context, "198.51.100.233");
        pharmacist.login(manager.username(), manager.password()).andExpect(status().isOk());
        pharmacist.post("/api/sales/" + note + "/cancel", "{\"reason\":\"Erreur de saisie (test)\"}")
                .andExpect(status().isOk());
        String cancelled = text(seller.get("/api/sales/" + note + "/pdf").andExpect(status().isOk()).andReturn());
        assertThat(cancelled).contains("ANNULÉ").contains("Erreur de saisie (test)");
    }

    @Test
    void theIdentityInForceAtTheIssuePrintsOnLaterReprints() throws Exception {
        profile("Ancienne Raison Sociale Démo", null);
        long quote = sale("{\"docType\":\"quote\",\"customerId\":" + customer() + "}", "1");
        seller.post("/api/sales/" + quote + "/confirm", "{}").andExpect(status().isOk());
        clock.advance(java.time.Duration.ofMinutes(5));
        profile("Nouvelle Raison Sociale Démo", null);

        String text = text(seller.get("/api/sales/" + quote + "/pdf").andExpect(status().isOk()).andReturn());
        assertThat(text).contains("Ancienne Raison Sociale Démo").doesNotContain("Nouvelle Raison Sociale Démo");
    }

    @Test
    void printingNeedsSaleRead() throws Exception {
        long quote = sale("{\"docType\":\"quote\",\"customerId\":" + customer() + "}", "1");
        seller.post("/api/sales/" + quote + "/confirm", "{}").andExpect(status().isOk());
        TestUser user = new TestUsers(jdbc, encoder).create("Accountant");
        jdbc.update("""
                DELETE FROM role_permissions WHERE role_id = (SELECT role_id FROM users WHERE id = ?)
                AND permission_id = (SELECT id FROM permissions WHERE code = 'sale.read')""", user.id());
        ApiClient other = ApiClient.create(context, "198.51.100.234");
        other.login(user.username(), user.password()).andExpect(status().isOk());
        other.get("/api/sales/" + quote + "/pdf").andExpect(status().isForbidden());
    }

    @Test
    void aStampedInvoicePrintsTheStampDutyAndTheNetInWords() throws Exception {
        jdbc.update("UPDATE app_settings SET setting_value = '1.00' WHERE setting_key = 'stamp_duty_rate'");
        jdbc.update("UPDATE app_settings SET setting_value = '100.00' WHERE setting_key = 'stamp_duty_step'");
        seller.post("/api/cash-sessions/open", "{\"openingAmount\":\"0\"}").andExpect(status().isCreated());
        long invoice = sale("{\"docType\":\"invoice\",\"customerId\":" + customer() + "}", "2");
        seller.post("/api/sales/" + invoice + "/confirm",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":\"2180.00\"}]}").andExpect(status().isOk());

        String a4 = text(seller.get("/api/sales/" + invoice + "/pdf").andExpect(status().isOk()).andReturn());
        assertThat(a4).contains("Total TTC").contains("2 180,00 DA").contains("Droit de timbre").contains("22,00 DA")
                .contains("Net à payer").contains("2 202,00 DA")
                .contains("Arrêtée la présente facture à la somme de : Deux mille deux cent deux dinars algériens.");
        String ticket = text(seller.get("/api/sales/" + invoice + "/pdf?format=ticket").andExpect(status().isOk())
                .andReturn());
        assertThat(ticket).contains("Droit de timbre").contains("Net à payer").contains("Espèces : 2 202,00 DA");
    }

    @Test
    void aGlobalDiscountPrintsTheGrossTheDiscountAndTheNet() throws Exception {
        seller.post("/api/cash-sessions/open", "{\"openingAmount\":\"0\"}").andExpect(status().isCreated());
        long receipt = sale("{\"docType\":\"receipt\"}", "2");
        admin.perform(MockMvcRequestBuilders.put("/api/sales/" + receipt + "/global-discount")
                .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"percent\",\"value\":\"10\"}"), true)
                .andExpect(status().isOk());
        seller.post("/api/sales/" + receipt + "/confirm",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":\"1962.00\"}]}").andExpect(status().isOk());

        String a4 = text(seller.get("/api/sales/" + receipt + "/pdf").andExpect(status().isOk()).andReturn());
        assertThat(a4).contains("Total HT").contains("2 000,00 DA").contains("Remise globale (")
                .contains("-200,00 DA").contains("Net HT").contains("1 800,00 DA").contains("1 962,00 DA");
        String ticket = text(seller.get("/api/sales/" + receipt + "/pdf?format=ticket").andExpect(status().isOk())
                .andReturn());
        assertThat(ticket).contains("Sous-total TTC").contains("2 180,00 DA").contains("-218,00 DA")
                .contains("1 962,00 DA");
    }

    @Test
    void aSaleWithoutStampDutyPrintsNoStampLine() throws Exception {
        seller.post("/api/cash-sessions/open", "{\"openingAmount\":\"0\"}").andExpect(status().isCreated());
        long receipt = sale("{\"docType\":\"receipt\"}", "1");
        seller.post("/api/sales/" + receipt + "/confirm",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":\"1090.00\"}]}").andExpect(status().isOk());
        assertThat(text(seller.get("/api/sales/" + receipt + "/pdf").andExpect(status().isOk()).andReturn()))
                .doesNotContain("Droit de timbre").doesNotContain("Net à payer");
    }

    private long sale(String draft, String quantity) throws Exception {
        long id = id(seller.post("/api/sales", draft).andExpect(status().isCreated()));
        seller.post("/api/sales/" + id + "/lines", "{\"productId\":" + product + ",\"quantity\":\"" + quantity + "\"}")
                .andExpect(status().isOk());
        return id;
    }

    private long customer() throws Exception {
        return id(seller.post("/api/customers", "{\"name\":\"Ferme Démo PDF " + System.nanoTime() + "\"}")
                .andExpect(status().isCreated()));
    }

    private void profile(String name, String nif) throws Exception {
        var request = MockMvcRequestBuilders.multipart("/api/company-profile").param("legalName", name);
        if (nif != null) {
            request.param("nif", nif);
        }
        request.file(new MockMultipartFile("logo", "logo.png", "image/png", png()));
        admin.perform(request, true).andExpect(status().isCreated());
    }

    /** A 1 × 1 PNG, so the logo goes through the renderer too. */
    private static byte[] png() {
        return java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=");
    }

    /** The text of every page, non-breaking spaces as spaces. */
    private static String text(MvcResult result) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(result.getResponse().getContentAsByteArray())) {
            List<String> pages = new ArrayList<>();
            pages.add(new PDFTextStripper().getText(pdf));
            return String.join("\n", pages).replace(' ', ' ').replace(' ', ' ');
        }
    }

    private static long id(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
