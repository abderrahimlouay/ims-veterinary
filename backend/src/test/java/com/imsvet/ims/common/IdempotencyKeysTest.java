package com.imsvet.ims.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.idempotency.IdempotencyKeys;
import com.imsvet.ims.common.idempotency.IdempotencyKeys.Claim;
import com.imsvet.ims.support.TestUsers;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotency keys (ADR-104, BR-PURCH-013, CT-05), on committed data: claimed in the caller's transaction before the
 * document exists, so the same key and body returns the first result, another body is {@code IDEMPOTENCY_CONFLICT},
 * a rolled-back document leaves no key, and a parallel duplicate waits for the first and then replays it.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class IdempotencyKeysTest {

    private static final String ENDPOINT = "POST /api/test-documents";

    record Request(long supplierId, BigDecimal quantity) {
    }

    @Autowired
    private IdempotencyKeys keys;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    private long user;

    @BeforeEach
    void user() {
        user = new TestUsers(jdbc, encoder).create("Pharmacist").id();
    }

    @Test
    void thereIsNoClaimOutsideATransaction() {
        assertThatThrownBy(() -> keys.claim(user, ENDPOINT, UUID.randomUUID().toString(), body("1"), "test_document"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theSameKeyAndBodyReturnsTheFirstResult() {
        String key = UUID.randomUUID().toString();
        assertThat(create(key, body("2.50"), 41)).isEmpty();

        assertThat(create(key, body("2.50"), 99)).contains(41L);
        assertThat(rows(key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT resource_type FROM idempotency_keys WHERE idem_key = ?", String.class,
                key)).isEqualTo("test_document");
    }

    @Test
    void anotherBodyWithTheSameKeyIsAConflict() {
        String key = UUID.randomUUID().toString();
        create(key, body("2.50"), 41);

        assertThatThrownBy(() -> create(key, body("3"), 42)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
        assertThat(rows(key)).isEqualTo(1);
    }

    @Test
    void aRolledBackDocumentLeavesNoKey() {
        String key = UUID.randomUUID().toString();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            keys.claim(user, ENDPOINT, key, body("1"), "test_document").complete(7);
            throw new IllegalStateException("line 3 failed");
        })).hasMessage("line 3 failed");
        assertThat(rows(key)).isZero();

        assertThat(create(key, body("1"), 8)).isEmpty();
    }

    @Test
    void keysAreScopedPerUserAndEndpoint() {
        String key = UUID.randomUUID().toString();
        long other = new TestUsers(jdbc, encoder).create("Pharmacist").id();
        create(key, body("1"), 1);

        assertThat(create(other, ENDPOINT, key, body("1"), 2)).isEmpty();
        assertThat(create(user, "POST /api/other-documents", key, body("1"), 3)).isEmpty();
        assertThat(rows(key)).isEqualTo(3);
    }

    @Test
    void withoutAKeyNothingIsStored() {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys", Long.class);

        assertThat(create(null, body("1"), 5)).isEmpty();
        assertThat(create(null, body("1"), 6)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys", Long.class)).isEqualTo(before);
    }

    @Test
    void aKeyThatIsNotAUuidIsRefused() {
        assertThatThrownBy(() -> create("retry-1", body("1"), 5)).isInstanceOfSatisfying(ValidationException.class,
                e -> assertThat(e.violations()).singleElement().satisfies(v -> {
                    assertThat(v.field()).isEqualTo("Idempotency-Key");
                    assertThat(v.code()).isEqualTo("PATTERN");
                }));
    }

    /** CT-05: a double submit racing on one key gives one document; the second waits, then gets the first. */
    @RepeatedTest(20)
    void aParallelDuplicateWaitsAndReplaysTheFirst() throws Exception {
        String key = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Optional<Long>>> results = new ArrayList<>();
            for (long resource = 100; resource < 102; resource++) {
                long id = resource;
                results.add(pool.submit(() -> {
                    start.await();
                    return create(key, body("1"), id);
                }));
            }
            start.countDown();
            List<Optional<Long>> outcomes = new ArrayList<>();
            for (Future<Optional<Long>> result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS));
            }

            assertThat(outcomes).filteredOn(Optional::isEmpty).hasSize(1);
            Long stored = jdbc.queryForObject("SELECT resource_id FROM idempotency_keys WHERE idem_key = ?", Long.class,
                    key);
            assertThat(outcomes).filteredOn(Optional::isPresent).singleElement().isEqualTo(Optional.of(stored));
            assertThat(rows(key)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** One use case: claims, "creates" the document {@code resourceId} unless replayed, commits. */
    private Optional<Long> create(String key, Request request, long resourceId) {
        return create(user, ENDPOINT, key, request, resourceId);
    }

    private Optional<Long> create(long userId, String endpoint, String key, Request request, long resourceId) {
        return transactions.execute(status -> {
            Claim claim = keys.claim(userId, endpoint, key, request, "test_document");
            if (claim.replayed().isEmpty()) {
                claim.complete(resourceId);
            }
            return claim.replayed();
        });
    }

    private static Request body(String quantity) {
        return new Request(3, new BigDecimal(quantity));
    }

    private int rows(String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE idem_key = ?", Integer.class, key);
    }
}
