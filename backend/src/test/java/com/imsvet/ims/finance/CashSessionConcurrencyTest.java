package com.imsvet.ims.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.finance.application.CashSessions;
import com.imsvet.ims.support.TestActor;
import com.imsvet.ims.support.TestUsers;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.security.crypto.password.PasswordEncoder;

/** CT-09: two sessions opened at once by the same cashier: one succeeds (BR-CASH-001). */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class CashSessionConcurrencyTest {

    @Autowired
    private CashSessions sessions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @RepeatedTest(20)
    void twoParallelOpeningsLeaveOneOpenSession() throws Exception {
        long cashier = new TestUsers(jdbc, encoder).create("Seller").id();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ErrorCode>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    TestActor.signIn(cashier, "cash.open");
                    try {
                        start.await();
                        sessions.open(BigDecimal.TEN);
                        return null;
                    } catch (BusinessException e) {
                        return e.code();
                    } finally {
                        TestActor.signOut();
                    }
                }));
            }
            start.countDown();
            List<ErrorCode> outcomes = new ArrayList<>();
            for (Future<ErrorCode> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).filteredOn(o -> o == null).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o != null).containsExactly(ErrorCode.CASH_SESSION_ALREADY_OPEN);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_sessions WHERE cashier_id = ? AND status = 'open'",
                Integer.class, cashier)).isOne();
    }
}
