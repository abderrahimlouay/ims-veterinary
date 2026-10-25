package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.sales.application.CustomerCredit;
import com.imsvet.ims.sales.application.CustomerCredit.CreditLimitInput;
import com.imsvet.ims.sales.application.CustomerDirectory;
import com.imsvet.ims.sales.application.CustomerDirectory.CustomerInput;
import com.imsvet.ims.support.MigrationAccount;
import com.imsvet.ims.support.TestActor;
import com.imsvet.ims.support.TestLots;
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
 * BR-CUST-002 on committed data: a credit-limit change whose CREDIT_LIMIT_CHANGE audit fails leaves the limit and
 * the version as they were; two users changing the limit at once from the same version — the row lock serialises
 * them and the second gets a concurrency failure (CONCURRENT_MODIFICATION), 20 times.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class CustomerCreditAtomicityTest {

    @Autowired
    private CustomerDirectory customers;

    @Autowired
    private CustomerCredit credit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MigrationAccount migration;

    private long operator;
    private long customer;

    @BeforeEach
    void customer() {
        operator = new TestLots(jdbc).fixtureUser();
        TestActor.signIn(operator, "customer.manage");
        customer = customers.create(new CustomerInput("Ferme Démo " + UUID.randomUUID().toString().substring(0, 8), null,
                null, null, null, null, null, null, null, null, null, null)).id();
        TestActor.signIn(operator, "customer.credit.manage");
    }

    @AfterEach
    void signOut() {
        TestActor.signOut();
    }

    @Test
    void aFailedAuditLeavesTheLimitAsItWas() {
        migration.jdbc().execute("""
                CREATE TRIGGER trg_test_audit_log_refuse_credit BEFORE INSERT ON audit_log FOR EACH ROW
                BEGIN
                    IF NEW.action = 'CREDIT_LIMIT_CHANGE' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log unavailable';
                    END IF;
                END""");
        try {
            assertThatThrownBy(() -> credit.change(customer, new CreditLimitInput(true, "5000", 0L)))
                    .hasStackTraceContaining("audit_log unavailable");
        } finally {
            migration.jdbc().execute("DROP TRIGGER trg_test_audit_log_refuse_credit");
        }

        assertThat(jdbc.queryForMap("SELECT CAST(credit_limit AS CHAR) AS l, version FROM customers WHERE id = ?",
                customer)).containsEntry("l", "0.00").containsEntry("version", 0L);
    }

    @RepeatedTest(20)
    void twoParallelChangesFromTheSameVersionLeaveOneWinner() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> outcomes = new ArrayList<>();
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (String limit : List.of("1000", "2000")) {
                futures.add(pool.submit(() -> {
                    TestActor.signIn(operator, "customer.credit.manage");
                    try {
                        start.await();
                        credit.change(customer, new CreditLimitInput(true, limit, 0L));
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
        assertThat(jdbc.queryForObject("SELECT version FROM customers WHERE id = ?", Long.class, customer)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'CREDIT_LIMIT_CHANGE' "
                + "AND entity_id = ?", Integer.class, customer)).isOne();
    }
}
