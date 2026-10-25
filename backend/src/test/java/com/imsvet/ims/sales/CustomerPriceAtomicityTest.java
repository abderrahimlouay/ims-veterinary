package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.sales.application.CustomerDirectory;
import com.imsvet.ims.sales.application.CustomerDirectory.CustomerInput;
import com.imsvet.ims.sales.application.CustomerPrices;
import com.imsvet.ims.support.MigrationAccount;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * BR-CUST-006 on committed data: a price whose PRICE_CHANGE audit fails is not written; two parallel creations of
 * the same customer × product price — the customer-row lock serialises them, one row exists and the other is refused
 * as a concurrent modification (never a raw constraint error), 20 times.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class CustomerPriceAtomicityTest {

    @Autowired
    private CustomerDirectory customers;

    @Autowired
    private CustomerPrices prices;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MigrationAccount migration;

    private long operator;
    private long customer;
    private long product;

    @BeforeEach
    void data() {
        TestLots lots = new TestLots(jdbc);
        String key = UUID.randomUUID().toString().substring(0, 8);
        operator = lots.fixtureUser();
        product = lots.product("CPA-" + key, "Prix parallèle");
        TestActor.signIn(operator, "customer.manage", "customer.price.manage");
        customer = customers.create(new CustomerInput("Ferme Démo " + key, null, null, null, null, null, null, null,
                null, null, null, null)).id();
    }

    @AfterEach
    void signOut() {
        TestActor.signOut();
    }

    @Test
    void aFailedAuditWritesNoPrice() {
        migration.jdbc().execute("""
                CREATE TRIGGER trg_test_audit_log_refuse_price BEFORE INSERT ON audit_log FOR EACH ROW
                BEGIN
                    IF NEW.action = 'PRICE_CHANGE' AND NEW.entity = 'customer_product_prices' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log unavailable';
                    END IF;
                END""");
        try {
            assertThatThrownBy(() -> prices.set(customer, product, new BigDecimal("1.25"), null))
                    .hasStackTraceContaining("audit_log unavailable");
        } finally {
            migration.jdbc().execute("DROP TRIGGER trg_test_audit_log_refuse_price");
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_product_prices WHERE customer_id = ?",
                Integer.class, customer)).isZero();
    }

    @RepeatedTest(20)
    void twoParallelCreationsOfTheSamePriceLeaveOneRow() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> outcomes = new ArrayList<>();
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (String price : List.of("1.10", "1.20")) {
                futures.add(pool.submit(() -> {
                    TestActor.signIn(operator, "customer.price.manage");
                    try {
                        start.await();
                        prices.set(customer, product, new BigDecimal(price), null);
                        return "ok";
                    } catch (ConcurrencyFailureException e) {
                        return "conflict";
                    } finally {
                        TestActor.signOut();
                    }
                }));
            }
            start.countDown();
            for (Future<String> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes).containsExactlyInAnyOrder("ok", "conflict");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_product_prices WHERE customer_id = ?",
                Integer.class, customer)).isOne();
    }
}
