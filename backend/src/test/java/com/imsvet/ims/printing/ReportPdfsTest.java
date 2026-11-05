package com.imsvet.ims.printing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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

/**
 * BR-RPT-007, ADR-149: every report prints as an A4 PDF with the company header, the filters, the export table and
 * its totals; wide reports in landscape; the report's own permission applies.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ReportPdfsTest {

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

    private ApiClient admin;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        today = calendar.today();
        TestUser user = new TestUsers(jdbc, encoder).create("Administrator");
        admin = ApiClient.create(context, "198.51.100.253");
        admin.login(user.username(), user.password()).andExpect(status().isOk());
    }

    @Test
    void wideReportsPrintInLandscapeWithTheirTotals() throws Exception {
        long operator = new TestLots(jdbc).fixtureUser();
        jdbc.update("""
                INSERT INTO customers (name, credit_limit, created_at, updated_at)
                VALUES ('Ferme Impression', NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""");
        long customer = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO sales_documents (doc_type, number, status, customer_id, is_credit_sale, due_date, issued_at,
                    total_ttc, created_by, created_at, updated_at)
                VALUES ('invoice', 'PDF-AGE', 'confirmed', ?, TRUE, ?, UTC_TIMESTAMP(6), ?, ?, UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6))""", customer, today.minusDays(45), new BigDecimal("1234.50"), operator);

        try (PDDocument pdf = pdf(admin.get("/api/reports/customer-balances?format=pdf"))) {
            PDRectangle page = pdf.getPage(0).getMediaBox();
            assertThat(page.getWidth()).isGreaterThan(page.getHeight());
            assertThat(text(pdf)).contains("Créances clients par ancienneté").contains("Situation au")
                    .contains("31–60 j").contains("Ferme Impression").contains("1 234,50").contains("Total");
        }
    }

    @Test
    void narrowReportsPrintInPortraitAndATraceShowsEverySection() throws Exception {
        try (PDDocument pdf = pdf(admin.get("/api/reports/revenue?format=pdf&groupBy=category"))) {
            PDRectangle page = pdf.getPage(0).getMediaBox();
            assertThat(page.getHeight()).isGreaterThan(page.getWidth());
            assertThat(text(pdf)).contains("Chiffre d'affaires").contains("Période : du")
                    .contains("Regroupement : par catégorie").contains("Bons de livraison non facturés");
        }
        long product = new TestLots(jdbc).product("PDF-TRC", "Produit imprimé");
        long lot = new TestLots(jdbc).lot(product, "LOT-PDF", today.plusYears(1), "6");
        try (PDDocument pdf = pdf(admin.get("/api/reports/lot-traceability?format=pdf&lotId=" + lot))) {
            assertThat(text(pdf)).contains("Traçabilité du lot LOT-PDF").contains("Mouvements")
                    .contains("Stock initial").contains("Amont : fournisseurs").contains("Aval : clients")
                    .contains("Aucune donnée.");
        }
    }

    @Test
    void theReportPermissionAndValidationApply() throws Exception {
        admin.get("/api/reports/cash-sessions?format=pdf&from=" + today.plusDays(1)).andExpect(status().isBadRequest());
        admin.get("/api/reports/lot-traceability?format=pdf&lotId=999999").andExpect(status().isNotFound());
        TestUser seller = new TestUsers(jdbc, encoder).create("Seller");
        ApiClient client = ApiClient.create(context, "198.51.100.254");
        client.login(seller.username(), seller.password()).andExpect(status().isOk());
        client.get("/api/reports/margin?format=pdf").andExpect(status().isForbidden());
        client.get("/api/reports/customer-balances?format=pdf").andExpect(status().isForbidden());
    }

    private static PDDocument pdf(ResultActions result) throws Exception {
        byte[] bytes = result.andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse()
                .getContentAsByteArray();
        return Loader.loadPDF(bytes);
    }

    private static String text(PDDocument pdf) throws Exception {
        return new PDFTextStripper().getText(pdf).replace('\u00A0', ' ');
    }
}
