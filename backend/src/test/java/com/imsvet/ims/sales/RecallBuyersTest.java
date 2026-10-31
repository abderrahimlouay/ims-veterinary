package com.imsvet.ims.sales;

import static org.hamcrest.Matchers.startsWith;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * The buyers of a recalled lot (BR-LOT-008, W6, ADR-117): every confirmed document that took its stock, anonymous sales
 * and delivery notes included; never a quote, a draft, a cancelled document or the invoice of a delivery note.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RecallBuyersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 2 × 10.00 HT + 9 % VAT. */
    private static final String TTC = "21.80";

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
    private long otherLot;
    private long customer;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        TestLots fixtures = new TestLots(jdbc);
        TestUser manager = new TestUsers(jdbc, encoder).create("Administrator");
        admin = ApiClient.create(context, "198.51.100.241");
        admin.login(manager.username(), manager.password()).andExpect(status().isOk());
        product = fixtures.product("RAPPEL-1", "Rappelé");
        jdbc.update("UPDATE products SET sale_price = 10 WHERE id = ?", product);
        lot = fixtures.lot(product, "L1", calendar.today().plusYears(1), "50");
        otherLot = fixtures.lot(fixtures.product("RAPPEL-2", "Autre"), "L2", calendar.today().plusYears(1), "50");
        customer = id(admin.post("/api/customers", "{\"name\":\"Ferme Rappel " + System.nanoTime()
                + "\",\"phone\":\"0550000001\"}").andExpect(status().isCreated()));
    }

    @Test
    void itListsEveryDocumentThatTookTheLotAndNothingElse() throws Exception {
        long invoice = draft("invoice", true);
        confirm(invoice, cheque());
        long receipt = draft("receipt", false);
        confirm(receipt, cheque());
        long note = draft("delivery_note", true);
        confirm(note, "{}");
        long noteInvoice = id(admin.post("/api/sales/" + note + "/convert", "{\"targetType\":\"invoice\"}")
                .andExpect(status().isCreated()));
        confirm(noteInvoice, cheque());
        long quote = draft("quote", true);
        confirm(quote, "{}");
        draft("invoice", true);
        long cancelled = draft("invoice", true);
        confirm(cancelled, cheque());
        admin.post("/api/sales/" + cancelled + "/cancel", "{\"reason\":\"erreur\"}").andExpect(status().isOk());
        entities.clear();

        admin.post("/api/lots/" + lot + "/recall", "{\"reason\":\"Avis du fabricant\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.buyers.length()").value(2))
                .andExpect(jsonPath("$.buyers[0].customerId").value(customer))
                .andExpect(jsonPath("$.buyers[0].customerName").value(startsWith("Ferme Rappel")))
                .andExpect(jsonPath("$.buyers[0].phone").value("0550000001"))
                .andExpect(jsonPath("$.buyers[0].documentNumber").value(startsWith("INV-")))
                .andExpect(jsonPath("$.buyers[0].documentType").value("invoice"))
                .andExpect(jsonPath("$.buyers[0].issuedAt").isNotEmpty())
                .andExpect(jsonPath("$.buyers[0].quantityBase").value("2.000"))
                .andExpect(jsonPath("$.buyers[1].documentNumber").value(startsWith("DN-")))
                .andExpect(jsonPath("$.anonymousSales.length()").value(1))
                .andExpect(jsonPath("$.anonymousSales[0].documentNumber").value(startsWith("RCT-")))
                .andExpect(jsonPath("$.remainingStockBase").value("44.000"));
        entities.clear();
        admin.get("/api/lots/" + lot + "/recall-report").andExpect(jsonPath("$.buyers.length()").value(2))
                .andExpect(jsonPath("$.anonymousSales.length()").value(1));
    }

    @Test
    void aDocumentIsOneRowHoweverManyOfItsLinesUseTheLot() throws Exception {
        long invoice = draft("invoice", true);
        admin.post("/api/sales/" + invoice + "/lines", "{\"productId\":" + product + ",\"quantity\":\"3\"}")
                .andExpect(status().isOk());
        confirm(invoice, cheque("54.50"));
        entities.clear();
        recall().andExpect(jsonPath("$.buyers.length()").value(1))
                .andExpect(jsonPath("$.buyers[0].quantityBase").value("5.000"));
    }

    @Test
    void anotherLotsSalesAreNotListed() throws Exception {
        confirm(draft("receipt", false), cheque());
        entities.clear();
        admin.post("/api/lots/" + otherLot + "/recall", "{\"reason\":\"Avis\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.buyers").isEmpty()).andExpect(jsonPath("$.anonymousSales").isEmpty());
    }

    @Test
    void aSaleAfterTheRecallIsImpossibleAndTheReportStaysTheSame() throws Exception {
        long late = draft("receipt", false);
        recall().andExpect(jsonPath("$.anonymousSales").isEmpty());
        entities.clear();
        admin.post("/api/sales/" + late + "/confirm", cheque()).andExpect(jsonPath("$.code").value("LOT_RECALLED"));
        entities.clear();
        recall().andExpect(jsonPath("$.anonymousSales").isEmpty());
    }

    private ResultActions recall() throws Exception {
        return admin.post("/api/lots/" + lot + "/recall", "{\"reason\":\"Avis du fabricant\"}")
                .andExpect(status().isOk());
    }

    private long draft(String type, boolean withCustomer) throws Exception {
        String party = withCustomer ? ",\"customerId\":" + customer : "";
        long draft = id(admin.post("/api/sales", "{\"docType\":\"" + type + "\"" + party + "}")
                .andExpect(status().isCreated()));
        admin.post("/api/sales/" + draft + "/lines", "{\"productId\":" + product + ",\"quantity\":\"2\"}")
                .andExpect(status().isOk());
        return draft;
    }

    private void confirm(long id, String json) throws Exception {
        admin.post("/api/sales/" + id + "/confirm", json).andExpect(status().isOk());
    }

    private static long id(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private static String cheque() {
        return cheque(TTC);
    }

    private static String cheque(String amount) {
        return "{\"payments\":[{\"method\":\"cheque\",\"amount\":\"" + amount + "\",\"reference\":\"CHQ-1\"}]}";
    }
}
