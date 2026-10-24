package com.imsvet.ims.purchasing;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.purchasing.application.PurchaseOrders;
import com.imsvet.ims.purchasing.application.PurchaseOrders.OrderInput;
import com.imsvet.ims.purchasing.application.PurchaseOrders.RequestedLine;
import com.imsvet.ims.support.TestActor;
import com.imsvet.ims.support.TestLots;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-027 on drafts (BR-PURCH-003): two simultaneous edits of one draft with the same version — one wins, the
 * other is a concurrency failure ({@code CONCURRENT_MODIFICATION} at the API) and changes nothing; 10 times on
 * committed data.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class PurchaseOrderConcurrencyTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PurchaseOrders orders;

    @RepeatedTest(10)
    void twoParallelEditsOfOneDraftCannotBothWin() throws Exception {
        TestLots lots = new TestLots(jdbc);
        String key = UUID.randomUUID().toString().substring(0, 8);
        long product = lots.product("POC-" + key, "Concurrent order");
        jdbc.update("INSERT INTO suppliers (name, created_at, updated_at) VALUES (?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                "Concurrent " + key);
        long supplier = jdbc.queryForObject("SELECT id FROM suppliers WHERE name = ?", Long.class, "Concurrent " + key);
        long operator = lots.fixtureUser();
        TestActor.signIn(operator, "purchase.create");
        long id;
        try {
            id = orders.create(input(supplier, product, "1")).id();
        } finally {
            TestActor.signOut();
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<String>> results = new ArrayList<>();
        for (String quantity : List.of("2", "3")) {
            results.add(pool.submit(() -> {
                TestActor.signIn(operator, "purchase.create");
                try {
                    start.await();
                    orders.update(id, input(supplier, product, quantity), 0);
                    return "ok";
                } catch (ConcurrencyFailureException e) {
                    return "CONCURRENT_MODIFICATION";
                } finally {
                    TestActor.signOut();
                }
            }));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> result : results) {
            outcomes.add(result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(outcomes).containsExactlyInAnyOrder("ok", "CONCURRENT_MODIFICATION");
        assertThat(jdbc.queryForList("SELECT CAST(quantity AS CHAR) FROM purchase_order_lines WHERE purchase_order_id = ?",
                String.class, id)).hasSize(1).first().isIn("2.000", "3.000");
    }

    private static OrderInput input(long supplier, long product, String quantity) {
        return new OrderInput(supplier, null, List.of(new RequestedLine(product, null, new BigDecimal(quantity),
                BigDecimal.ONE, null, null)));
    }
}
