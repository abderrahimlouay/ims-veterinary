package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.inventory.application.StockService;
import com.imsvet.ims.inventory.application.StockService.Movement;
import com.imsvet.ims.inventory.domain.MovementType;
import com.imsvet.ims.support.StockAssertions;
import com.imsvet.ims.support.TestActor;
import com.imsvet.ims.support.TestLots;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Concurrency of {@code StockService} on committed data (testing.md §6), each scenario repeated 20 times.
 * CT-01: N parallel withdrawals of the last units of a lot — exactly the stock succeeds, the rest is refused, the
 * balance never goes negative (BR-STOCK-001). Until sales exist they are manual adjustments (user decision
 * 2026-10-22; re-run with sales in Phase 7). CT-02: operations taking lots A,B and B,A at once do not deadlock,
 * because lots are locked in ascending id order (ADR-012, BR-STOCK-012). Ledger = balance after each run.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class StockConcurrencyTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StockService stock;

    @Autowired
    private TransactionTemplate transactions;

    @RepeatedTest(20)
    void ct01ParallelWithdrawalsOfTheLastUnits() throws Exception {
        TestLots lots = new TestLots(jdbc);
        String key = UUID.randomUUID().toString().substring(0, 8);
        long product = lots.product("CT01-" + key, "CT-01");
        long lot = lots.lot(product, "L-" + key, LocalDate.of(2027, 5, 31), "5");
        long operator = lots.fixtureUser();

        List<String> outcomes = runInParallel(10, i -> () -> withdraw(operator, List.of(
                new Movement(lot, product, MovementType.ADJUSTMENT_BREAKAGE, new BigDecimal("-1"), "CT-01", null, null))));

        assertThat(outcomes.stream().filter("ok"::equals)).hasSize(5);
        assertThat(outcomes.stream().filter("STOCK_INSUFFICIENT"::equals)).hasSize(5);
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", String.class, lot))
                .isEqualTo("0.000");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(lot));
    }

    @RepeatedTest(20)
    void ct02OppositeLockOrdersDoNotDeadlock() throws Exception {
        TestLots lots = new TestLots(jdbc);
        String key = UUID.randomUUID().toString().substring(0, 8);
        long product = lots.product("CT02-" + key, "CT-02");
        long a = lots.lot(product, "A-" + key, LocalDate.of(2027, 5, 31), "100");
        long b = lots.lot(product, "B-" + key, LocalDate.of(2027, 6, 30), "100");
        long operator = lots.fixtureUser();

        List<String> outcomes = runInParallel(10, i -> () -> withdraw(operator, i % 2 == 0
                ? List.of(take(a, product), take(b, product))
                : List.of(take(b, product), take(a, product))));

        assertThat(outcomes).containsOnly("ok");
        assertThat(jdbc.queryForObject("SELECT SUM(quantity_on_hand) FROM lots WHERE id IN (?, ?)", String.class, a, b))
                .isEqualTo("180.000");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(a, b));
    }

    private static Movement take(long lot, long product) {
        return new Movement(lot, product, MovementType.ADJUSTMENT_INTERNAL_USE, new BigDecimal("-1"), "CT-02", null, null);
    }

    /** One withdrawal in its own transaction, as a use case would run it. */
    private String withdraw(long operator, List<Movement> movements) {
        TestActor.signIn(operator);
        try {
            transactions.executeWithoutResult(status -> stock.post(movements));
            return "ok";
        } catch (BusinessException e) {
            return e.code().name();
        } finally {
            TestActor.signOut();
        }
    }

    private static List<String> runInParallel(int threads, IntFunction<Callable<String>> task)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<String> work = task.apply(i);
            futures.add(pool.submit(() -> {
                start.await();
                return work.call();
            }));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> future : futures) {
            outcomes.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }
}
