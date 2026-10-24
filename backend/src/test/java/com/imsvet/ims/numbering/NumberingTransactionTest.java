package com.imsvet.ims.numbering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestClock;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.inventory.application.StockService;
import com.imsvet.ims.inventory.application.StockService.Movement;
import com.imsvet.ims.inventory.domain.MovementType;
import com.imsvet.ims.numbering.application.NumberingService;
import com.imsvet.ims.numbering.domain.DocumentKey;
import com.imsvet.ims.support.StockAssertions;
import com.imsvet.ims.support.TestActor;
import com.imsvet.ims.support.TestLots;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Numbering in the caller's transaction (ADR-011, ADR-097), on committed data: no number outside a transaction;
 * a rollback gives the number back (no gap); the counter row stays locked until the caller commits; CT-03, 1,000
 * parallel allocations unique and gap-free, 20 times; and the lock order of database.md §7 (counter row, then lots
 * in ascending id) never deadlocks two documents that touch the same lots in opposite orders, 20 times. Each test
 * uses its own year so the counts are exact.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class NumberingTransactionTest {

    private static final int ALLOCATIONS = 1_000;
    private static final int THREADS = 16;

    @Autowired
    private NumberingService numbering;

    @Autowired
    private StockService stock;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void thereIsNoNumberOutsideATransaction() {
        assertThatThrownBy(() -> numbering.next(DocumentKey.PO)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aRollbackGivesTheNumberBack() {
        clock.set(Instant.parse("2040-06-01T09:00:00Z"));
        assertThat(allocate(DocumentKey.PO)).isEqualTo("PO-2040-000001");

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            assertThat(numbering.next(DocumentKey.PO)).isEqualTo("PO-2040-000002");
            throw new IllegalStateException("document insert failed");
        })).hasMessage("document insert failed");

        assertThat(allocate(DocumentKey.PO)).as("no gap").isEqualTo("PO-2040-000002");
    }

    @Test
    void theCounterStaysLockedUntilTheCallerCommits() throws Exception {
        clock.set(Instant.parse("2041-06-01T09:00:00Z"));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<String> first = pool.submit(() -> transactions.execute(status -> {
            String number = numbering.next(DocumentKey.GR);
            holding.countDown();
            await(release);
            return number;
        }));
        holding.await(10, TimeUnit.SECONDS);
        Future<String> second = pool.submit(() -> allocate(DocumentKey.GR));

        assertThatThrownBy(() -> second.get(1, TimeUnit.SECONDS)).as("waits for the first transaction")
                .isInstanceOf(TimeoutException.class);
        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("GR-2041-000001");
        assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("GR-2041-000002");
        pool.shutdown();
    }

    @RepeatedTest(20)
    void ct03ParallelAllocationsAreUniqueAndGapFree(RepetitionInfo repetition) throws Exception {
        int year = 2100 + repetition.getCurrentRepetition();
        clock.set(Instant.parse(year + "-06-01T09:00:00Z"));
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < ALLOCATIONS; i++) {
            results.add(pool.submit(() -> allocate(DocumentKey.SR)));
        }
        Set<String> numbers = new HashSet<>();
        for (Future<String> result : results) {
            numbers.add(result.get(120, TimeUnit.SECONDS));
        }
        pool.shutdown();

        Set<String> expected = new HashSet<>();
        for (int n = 1; n <= ALLOCATIONS; n++) {
            expected.add("SR-%d-%06d".formatted(year, n));
        }
        assertThat(numbers).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT last_number FROM document_sequences WHERE doc_key = 'SR' AND year = ?",
                Integer.class, year)).isEqualTo(ALLOCATIONS);
    }

    @RepeatedTest(20)
    void numberThenLotsInOppositeOrdersNeverDeadlocks(RepetitionInfo repetition) throws Exception {
        clock.set(Instant.parse((2200 + repetition.getCurrentRepetition()) + "-06-01T09:00:00Z"));
        TestLots lots = new TestLots(jdbc);
        String key = UUID.randomUUID().toString().substring(0, 8);
        long product = lots.product("NUM-" + key, "Numbered");
        long a = lots.lot(product, "A-" + key, LocalDate.of(2027, 5, 31), "100");
        long b = lots.lot(product, "B-" + key, LocalDate.of(2027, 6, 30), "100");
        long operator = lots.fixtureUser();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            List<Movement> movements = i % 2 == 0 ? List.of(take(a, product), take(b, product))
                    : List.of(take(b, product), take(a, product));
            results.add(pool.submit(() -> {
                TestActor.signIn(operator);
                try {
                    start.await();
                    return transactions.execute(status -> {
                        String number = numbering.next(DocumentKey.GR);
                        stock.post(movements);
                        return number;
                    });
                } catch (BusinessException e) {
                    return e.code().name();
                } finally {
                    TestActor.signOut();
                }
            }));
        }
        start.countDown();
        Set<String> numbers = new HashSet<>();
        for (Future<String> result : results) {
            numbers.add(result.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(numbers).hasSize(10).allMatch(n -> n.startsWith("GR-"));
        assertThat(jdbc.queryForObject("SELECT SUM(quantity_on_hand) FROM lots WHERE id IN (?, ?)", String.class, a, b))
                .isEqualTo("180.000");
        StockAssertions.assertLedgerMatchesBalances(jdbc, List.of(a, b));
    }

    private String allocate(DocumentKey key) {
        return transactions.execute(status -> numbering.next(key));
    }

    private static Movement take(long lot, long product) {
        return new Movement(lot, product, MovementType.ADJUSTMENT_INTERNAL_USE, new BigDecimal("-1"), "Numbering order",
                null, null);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
