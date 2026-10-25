package com.imsvet.ims.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.sales.application.CustomerDirectory;
import com.imsvet.ims.sales.application.CustomerDirectory.CustomerInput;
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
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * BR-CUST-005 on committed data, 20 times each: two parallel creations of the same customer (same name and phone in
 * other formats; same tax id) — the unique keys let one through and the other is refused DUPLICATE_RESOURCE; the
 * same name without a phone — both are accepted (a missing phone never conflicts).
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class CustomerConcurrencyTest {

    @Autowired
    private CustomerDirectory customers;

    @Autowired
    private JdbcTemplate jdbc;

    @RepeatedTest(20)
    void twoParallelCreationsWithTheSameNameAndPhoneLeaveOne() throws Exception {
        String key = UUID.randomUUID().toString().substring(0, 8);
        List<Object> outcomes = race(input("Ferme Démo " + key, "0555 01 02 03", null),
                input("FERME DEMO " + key, "+213 555-01-02-03", null));

        assertThat(outcomes).filteredOn(o -> o == null).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o != null).containsExactly(ErrorCode.DUPLICATE_RESOURCE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customers WHERE name = ?", Integer.class,
                "Ferme Démo " + key)).isOne();
    }

    @RepeatedTest(20)
    void twoParallelCreationsWithTheSameTaxIdLeaveOne() throws Exception {
        String key = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        List<Object> outcomes = race(input("Démo A " + key, null, "NIF " + key),
                input("Démo B " + key, null, "nif" + key.toLowerCase()));

        assertThat(outcomes).filteredOn(o -> o == null).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o != null).containsExactly(ErrorCode.DUPLICATE_RESOURCE);
    }

    /** ADR-110: without a phone the same name never conflicts, also in parallel. */
    @RepeatedTest(20)
    void twoParallelCreationsWithTheSameNameAndNoPhoneAreBothAccepted() throws Exception {
        String key = UUID.randomUUID().toString().substring(0, 8);
        List<Object> outcomes = race(input("Ferme Démo " + key, null, null), input("ferme demo " + key, " ", null));

        assertThat(outcomes).containsExactly(null, null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customers WHERE name = ?", Integer.class,
                "Ferme Démo " + key)).isEqualTo(2);
    }

    private static CustomerInput input(String name, String phone, String taxId) {
        return new CustomerInput(name, null, null, null, null, null, null, phone, null, taxId, null, null);
    }

    private List<Object> race(CustomerInput first, CustomerInput second) throws Exception {
        long operator = new TestLots(jdbc).fixtureUser();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (CustomerInput input : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    TestActor.signIn(operator, "customer.manage");
                    try {
                        start.await();
                        customers.create(input);
                        return null;
                    } catch (BusinessException e) {
                        return e.code();
                    } finally {
                        TestActor.signOut();
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
