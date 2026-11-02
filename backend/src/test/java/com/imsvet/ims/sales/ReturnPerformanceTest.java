package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.support.ApiClient;
import com.imsvet.ims.support.TestLots;
import com.imsvet.ims.support.TestUsers;
import com.imsvet.ims.support.TestUsers.TestUser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * A customer return within the same bound as a sale (NFR, roadmap Phase 8), on the server: the preview and then the
 * return of one unit on each of the 5 lines of a cash receipt, with a cheque refund, 20 times on the same receipt, so
 * each return reads the earlier ones' credits (ADR-130). Each preview plus return stays under 2 s. No network and no
 * screen are measured; this is a guard, not a load test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ReturnPerformanceTest {

    private static final Logger LOG = LoggerFactory.getLogger(ReturnPerformanceTest.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long LIMIT_MILLIS = 2_000;
    private static final int RETURNS = 20;

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

    @Test
    void aWholeReturnTakesUnderTwoSeconds() throws Exception {
        clock.reset();
        TestLots fixtures = new TestLots(jdbc);
        TestUser user = new TestUsers(jdbc, encoder).create("Pharmacist");
        ApiClient pharmacist = ApiClient.create(context, "198.51.100.252");
        pharmacist.login(user.username(), user.password()).andExpect(status().isOk());
        StringBuilder receipt = new StringBuilder();
        long id = JSON.readTree(pharmacist.post("/api/sales", "{\"docType\":\"receipt\"}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        for (int i = 0; i < 5; i++) {
            long product = fixtures.product("BACK-" + i, "Retour rapide " + i);
            jdbc.update("UPDATE products SET sale_price = 10 WHERE id = ?", product);
            fixtures.lot(product, "B" + i, calendar.today().plusYears(1), "1000");
            pharmacist.post("/api/sales/" + id + "/lines", "{\"productId\":" + product + ",\"quantity\":\"50\"}")
                    .andExpect(status().isOk());
        }
        String ttc = JSON.readTree(pharmacist.get("/api/sales/" + id).andReturn().getResponse().getContentAsString())
                .get("totals").get("ttc").asString();
        pharmacist.post("/api/sales/" + id + "/confirm", "{\"payments\":[{\"method\":\"cheque\",\"amount\":\"" + ttc
                + "\",\"reference\":\"CHQ-P\"}]}").andExpect(status().isOk());
        List<Long> lines = jdbc.queryForList("SELECT id FROM sales_document_lines WHERE document_id = ? ORDER BY id",
                Long.class, id);
        for (long line : lines) {
            receipt.append(receipt.isEmpty() ? "" : ",").append("{\"saleLineId\":").append(line)
                    .append(",\"quantity\":\"1\",\"unit\":\"base\",\"sealed\":true,\"disposition\":\"restock\"}");
        }
        String returned = "[" + receipt + "]";

        List<Long> times = new ArrayList<>();
        for (int i = 0; i < RETURNS; i++) {
            long start = System.nanoTime();
            String credit = JSON.readTree(pharmacist.post("/api/returns/customer/preview", "{\"documentId\":" + id
                    + ",\"lines\":" + returned + "}").andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString()).get("creditNote").get("totalTtc").asString();
            pharmacist.post("/api/returns/customer", "{\"documentId\":" + id + ",\"reason\":\"Mesure\",\"lines\":"
                    + returned + ",\"refund\":[{\"method\":\"cheque\",\"amount\":\"" + credit
                    + "\",\"reference\":\"CHQ-R\"}]}").andExpect(status().isCreated());
            times.add((System.nanoTime() - start) / 1_000_000);
        }
        Collections.sort(times);
        LOG.info("A return of 5 lines with its preview, {} ms median, {} ms slowest", times.get(RETURNS / 2),
                times.getLast());
        assertThat(times.getLast()).isLessThan(LIMIT_MILLIS);
        assertThat(jdbc.queryForObject("SELECT SUM(returned_quantity_base) FROM sales_document_lines WHERE document_id = ?",
                Integer.class, id)).isEqualTo(5 * RETURNS);
    }
}
