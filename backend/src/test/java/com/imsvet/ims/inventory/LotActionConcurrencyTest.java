package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.TestClock;
import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.inventory.application.LotActions;
import com.imsvet.ims.support.TestLots;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Concurrency CT-10 (testing.md §6, ADR-012), repeated 20 times: lot actions lock the lot row ({@code FOR UPDATE}), so two simultaneous
 * blocks of one lot cannot both succeed: one blocks, the other sees the blocked lot and is refused, and exactly one
 * audit entry is written. Runs on committed data, without a test transaction, in the database for committed data
 * (the lot's opening movement can never be deleted).
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class LotActionConcurrencyTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LotActions actions;

    @Autowired
    private TestClock clock;

    private long product;
    private long lot;

    @BeforeEach
    void committedLot() {
        clock.reset();
        TestLots lots = new TestLots(jdbc);
        String key = UUID.randomUUID().toString().substring(0, 8);
        product = lots.product("CONC-" + key, "Concurrent");
        lot = lots.lot(product, "L-" + key, LocalDate.of(2027, 5, 31), "10");
    }

    @RepeatedTest(20)
    void twoSimultaneousBlocksCannotBothSucceed() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String reason = "Reason " + i;
            results.add(pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        "pharmacist", null, List.of(new SimpleGrantedAuthority("lot.block"))));
                start.await();
                try {
                    actions.block(lot, reason);
                    return "ok";
                } catch (BusinessException e) {
                    return e.code().name();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> result : results) {
            outcomes.add(result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(outcomes).containsExactlyInAnyOrder("ok", ErrorCode.INVALID_STATE_TRANSITION.name());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'LOT_BLOCK' AND entity_id = ?",
                Integer.class, lot)).isEqualTo(1);
    }
}
