package com.imsvet.ims.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.inventory.application.LotIdentities;
import com.imsvet.ims.inventory.infrastructure.LotRepository;
import com.imsvet.ims.support.TestLots;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slice 5 follow-up: the created flag of find-or-create rests on LAST_INSERT_ID, which is per connection. These
 * tests fail if the reset, the insert and the read ever stop sharing one connection and one transaction (pool,
 * driver or repository change), instead of the flag silently becoming wrong.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LotIdentitiesTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LotRepository lots;

    @Autowired
    private LotIdentities identities;

    @Test
    @Transactional
    void resetInsertAndReadShareTheConnectionOfTheTransaction() {
        long connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
        long product = new TestLots(jdbc).product("IDENT-1", "Identity");

        lots.resetLastInsertId();
        assertThat(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)).isEqualTo(connection);
        lots.insertIfAbsent(product, "LI-1", LocalDate.of(2027, 1, 31), BigDecimal.ZERO, Instant.EPOCH);
        long inserted = lots.lastInsertId();

        assertThat(inserted).isEqualTo(jdbc.queryForObject("SELECT id FROM lots WHERE lot_number = 'LI-1'", Long.class));
        lots.resetLastInsertId();
        lots.insertIfAbsent(product, "LI-1", LocalDate.of(2027, 1, 31), BigDecimal.ZERO, Instant.EPOCH);
        assertThat(lots.lastInsertId()).isZero();
        assertThat(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)).isEqualTo(connection);
    }

    @Test
    @Transactional
    void createdIsTrueOnlyForTheCallThatInsertedTheLot() {
        long product = new TestLots(jdbc).product("IDENT-2", "Identity");

        assertThat(identities.findOrCreate(product, "LI-2", LocalDate.of(2027, 1, 31), BigDecimal.ONE).created()).isTrue();
        assertThat(identities.findOrCreate(product, "li-2", LocalDate.of(2027, 1, 31), BigDecimal.ONE).created()).isFalse();
    }

    @Test
    void findOrCreateRefusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> identities.findOrCreate(1, "LI-3", LocalDate.of(2027, 1, 31), BigDecimal.ONE))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
