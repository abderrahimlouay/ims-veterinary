package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.inventory.application.OpeningStock;
import com.imsvet.ims.inventory.application.OpeningStock.OpeningInput;
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
 * BR-LOT-002 under concurrency (as CT-08 will for receipts): simultaneous opening-stock entries of the same new lot
 * number create one lot whose balance is their sum; repeated 20 times on committed data.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class OpeningStockConcurrencyTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OpeningStock openingStock;

    @RepeatedTest(20)
    void simultaneousEntriesOfOneNewLotNumberMakeOneLot() throws Exception {
        TestLots lots = new TestLots(jdbc);
        String key = UUID.randomUUID().toString().substring(0, 8);
        long product = lots.product("OPEN-" + key, "Concurrent opening");
        long operator = lots.fixtureUser();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> {
                TestActor.signIn(operator, "stock.adjust");
                try {
                    start.await();
                    openingStock.enter(new OpeningInput(product, "N-" + key, LocalDate.of(2027, 3, 31),
                            new BigDecimal("1"), null, new BigDecimal("0.5")));
                    return null;
                } finally {
                    TestActor.signOut();
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        List<Long> created = jdbc.queryForList("SELECT id FROM lots WHERE product_id = ?", Long.class, product);
        assertThat(created).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM lots WHERE id = ?", String.class, created.getFirst()))
                .isEqualTo("8.000");
        StockAssertions.assertLedgerMatchesBalances(jdbc, created);
    }
}
