package com.imsvet.ims.purchasing;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.purchasing.application.SupplierReturns;
import com.imsvet.ims.purchasing.application.SupplierReturns.ReturnInput;
import com.imsvet.ims.purchasing.application.SupplierReturns.ReturnedLine;
import com.imsvet.ims.support.StockAssertions;
import com.imsvet.ims.support.TestActor;
import com.imsvet.ims.support.TestLots;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * BR-PURCH-009 and BR-STOCK-001 on committed data, 20 times: two parallel returns that together overdraw one lot —
 * one is confirmed, the other refused with {@code STOCK_INSUFFICIENT}; the lot never goes negative and its ledger
 * equals its balance; the refused return consumes no SR number.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class SupplierReturnConcurrencyTest {

    @Autowired
    private SupplierReturns returns;

    @Autowired
    private JdbcTemplate jdbc;

    @RepeatedTest(20)
    void twoReturnsOverdrawingOneLotLeaveOneRefused() throws Exception {
        TestLots lots = new TestLots(jdbc);
        String key = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO suppliers (name, created_at, updated_at) VALUES (?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                "Overdraw " + key);
        long supplier = jdbc.queryForObject("SELECT id FROM suppliers WHERE name = ?", Long.class, "Overdraw " + key);
        long lot = lots.lot(lots.product("OVR-" + key, "Overdraw"), "L-" + key, LocalDate.of(2027, 5, 31), "10");
        long operator = lots.fixtureUser();
        int counter = srCounter();
        ReturnInput six = new ReturnInput(supplier, "Damaged", List.of(new ReturnedLine(lot, new BigDecimal("6"), null)));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Object> outcomes = new ArrayList<>();
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    TestActor.signIn(operator, "return.supplier.create");
                    try {
                        start.await();
                        returns.create(six);
                        return null;
                    } catch (BusinessException e) {
                        return e.code();
                    } finally {
                        TestActor.signOut();
                    }
                }));
            }
            start.countDown();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes).filteredOn(o -> o == null).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o != null).containsExactly(ErrorCode.STOCK_INSUFFICIENT);
        assertThat(jdbc.queryForObject("SELECT CAST(quantity_on_hand AS CHAR) FROM lots WHERE id = ?", String.class,
                lot)).isEqualTo("4.000");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
        assertThat(srCounter()).as("the refused return gave its number back").isEqualTo(counter + 1);
    }

    private int srCounter() {
        return jdbc.queryForObject("""
                SELECT COALESCE(MAX(last_number), 0) FROM document_sequences WHERE doc_key = 'SR' AND year = 2026""",
                Integer.class);
    }
}
