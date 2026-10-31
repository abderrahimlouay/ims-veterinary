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
 * "A sale &lt; 2 s" (roadmap Phase 7), on the server: a credit sale of 5 products, from the draft through the lines to
 * the confirmation, for a customer with 2,000 credit invoices and their payments behind the limit check. Each of 20 sales
 * stays under the limit. No network and no screen are measured; this is a guard, not a load test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SalePerformanceTest {

    private static final Logger LOG = LoggerFactory.getLogger(SalePerformanceTest.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long LIMIT_MILLIS = 2_000;
    private static final int HISTORY = 2_000;
    private static final int SALES = 20;

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
    void aWholeSaleTakesUnderTwoSeconds() throws Exception {
        clock.reset();
        TestLots fixtures = new TestLots(jdbc);
        TestUser user = new TestUsers(jdbc, encoder).create("Seller");
        ApiClient seller = ApiClient.create(context, "198.51.100.251");
        seller.login(user.username(), user.password()).andExpect(status().isOk());
        long operator = fixtures.fixtureUser();
        List<Long> products = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long product = fixtures.product("SPEED-" + i, "Rapide " + i);
            jdbc.update("UPDATE products SET sale_price = 10 WHERE id = ?", product);
            fixtures.lot(product, "S" + i, calendar.today().plusYears(1), "1000");
            products.add(product);
        }
        long customer = JSON.readTree(seller.post("/api/customers", "{\"name\":\"Ferme Rapide " + System.nanoTime() + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        jdbc.update("UPDATE customers SET credit_limit = NULL WHERE id = ?", customer);
        history(customer, operator);

        List<Long> times = new ArrayList<>();
        for (int sale = 0; sale < SALES; sale++) {
            long start = System.nanoTime();
            long id = JSON.readTree(seller.post("/api/sales", "{\"docType\":\"invoice\",\"creditSale\":true,\"customerId\":"
                    + customer + "}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                    .get("id").asLong();
            for (long product : products) {
                seller.post("/api/sales/" + id + "/lines", "{\"productId\":" + product + ",\"quantity\":\"1\"}")
                        .andExpect(status().isOk());
            }
            seller.post("/api/sales/" + id + "/confirm", "{}").andExpect(status().isOk());
            times.add((System.nanoTime() - start) / 1_000_000);
        }
        Collections.sort(times);
        LOG.info("A sale of 5 lines, {} ms median, {} ms slowest", times.get(SALES / 2), times.getLast());
        assertThat(times.getLast()).isLessThan(LIMIT_MILLIS);
    }

    private void history(long customer, long operator) {
        List<Object[]> invoices = new ArrayList<>();
        List<Object[]> payments = new ArrayList<>();
        for (int i = 0; i < HISTORY; i++) {
            invoices.add(new Object[] {"SPEED-" + i, customer, operator});
            payments.add(new Object[] {customer, operator});
        }
        jdbc.batchUpdate("""
                INSERT INTO sales_documents (doc_type, number, status, customer_id, is_credit_sale, due_date, issued_at,
                                             total_ttc, created_by, created_at, updated_at)
                VALUES ('invoice', ?, 'confirmed', ?, TRUE, CURRENT_DATE, UTC_TIMESTAMP(6), 10, ?, UTC_TIMESTAMP(6),
                        UTC_TIMESTAMP(6))""", invoices);
        jdbc.batchUpdate("""
                INSERT INTO customer_payments (customer_id, method, amount, reference, paid_at, created_by)
                VALUES (?, 'cheque', 10, 'CHQ', UTC_TIMESTAMP(6), ?)""", payments);
    }
}
