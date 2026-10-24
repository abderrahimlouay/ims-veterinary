package com.imsvet.ims.purchasing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.CommittedDataDatabase;
import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.purchasing.application.GoodsReceipts;
import com.imsvet.ims.purchasing.application.GoodsReceipts.ReceiptInput;
import com.imsvet.ims.purchasing.application.GoodsReceipts.ReceivedLine;
import com.imsvet.ims.purchasing.application.PurchaseOrders;
import com.imsvet.ims.purchasing.application.PurchaseOrders.OrderInput;
import com.imsvet.ims.purchasing.application.PurchaseOrders.RequestedLine;
import com.imsvet.ims.support.MigrationAccount;
import com.imsvet.ims.support.TestActor;
import com.imsvet.ims.support.TestLots;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * BR-PURCH-007 on committed data: a receipt commits whole or not at all. A refusal on the third line (a recalled
 * lot, found after the first two lots were created), and separately a failed audit write while blocking a
 * non-conforming lot (a trigger refusing the {@code LOT_BLOCK} row), leave no lot, no movement, no receipt, no
 * price entry, no number consumed, no idempotency key, the order and the lots' status as they were. A cancellation
 * whose {@code LOT_UNBLOCK} audit fails leaves the receipt, the lots, the order and the price entries as they were.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CommittedDataDatabase.class })
class GoodsReceiptAtomicityTest {

    private static final LocalDate EXPIRY = LocalDate.of(2027, 6, 30);

    @Autowired
    private GoodsReceipts receipts;

    @Autowired
    private PurchaseOrders orders;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MigrationAccount migration;

    private String key;
    private long supplier;
    private long product;
    private long recalled;
    private long order;
    private long poLine;

    @BeforeEach
    void sentOrder() {
        TestLots lots = new TestLots(jdbc);
        key = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO suppliers (name, created_at, updated_at) VALUES (?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                "Atomic " + key);
        supplier = jdbc.queryForObject("SELECT id FROM suppliers WHERE name = ?", Long.class, "Atomic " + key);
        product = lots.product("ATOM-" + key, "Atomic receipt");
        recalled = lots.lot(product, "R-" + key, EXPIRY, "4", "recalled");
        TestActor.signIn(lots.fixtureUser(), "purchase.create", "purchase.validate", "receipt.create");
        order = orders.create(new OrderInput(supplier, null, List.of(new RequestedLine(product, null,
                new BigDecimal("100"), new BigDecimal("0.5"), null, null)))).id();
        orders.validate(order);
        poLine = orders.send(order).lines().getFirst().id();
    }

    @AfterEach
    void signOut() {
        TestActor.signOut();
    }

    @Test
    void aRefusalOnTheThirdLineRollsBackTheLotsAlreadyCreated() {
        int counter = grCounter();
        String idem = UUID.randomUUID().toString();

        assertThatThrownBy(() -> receipts.confirm(input(line("N1-" + key, true), line("N2-" + key, true),
                line("R-" + key, true)), idem)).isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.LOT_RECALLED));

        assertNothingChanged(counter, idem);
    }

    @Test
    void aFailedAuditWhileBlockingRollsEverythingBack() {
        int counter = grCounter();
        String idem = UUID.randomUUID().toString();
        migration.jdbc().execute("""
                CREATE TRIGGER trg_test_audit_log_refuse_lot_block BEFORE INSERT ON audit_log FOR EACH ROW
                BEGIN
                    IF NEW.action = 'LOT_BLOCK' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log unavailable';
                    END IF;
                END""");
        try {
            assertThatThrownBy(() -> receipts.confirm(input(line("N1-" + key, true), line("N2-" + key, false)), idem))
                    .hasStackTraceContaining("audit_log unavailable");
        } finally {
            migration.jdbc().execute("DROP TRIGGER trg_test_audit_log_refuse_lot_block");
        }

        assertNothingChanged(counter, idem);
    }

    /** BR-PURCH-012: a cancellation whose LOT_UNBLOCK audit fails changes nothing either. */
    @Test
    void aFailedAuditWhileCancellingRollsEverythingBack() {
        long receipt = receipts.confirm(input(line("N1-" + key, false), line("N2-" + key, true)), null).id();
        Long blocked = jdbc.queryForObject("SELECT id FROM lots WHERE lot_number = ?", Long.class, "N1-" + key);
        migration.jdbc().execute("""
                CREATE TRIGGER trg_test_audit_log_refuse_lot_unblock BEFORE INSERT ON audit_log FOR EACH ROW
                BEGIN
                    IF NEW.action = 'LOT_UNBLOCK' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log unavailable';
                    END IF;
                END""");
        TestActor.signIn(new TestLots(jdbc).fixtureUser(), "receipt.cancel");
        try {
            assertThatThrownBy(() -> receipts.cancel(receipt, "Wrong delivery"))
                    .hasStackTraceContaining("audit_log unavailable");
        } finally {
            migration.jdbc().execute("DROP TRIGGER trg_test_audit_log_refuse_lot_unblock");
        }

        assertThat(jdbc.queryForObject("SELECT status FROM goods_receipts WHERE id = ?", String.class, receipt))
                .isEqualTo("confirmed");
        assertThat(jdbc.queryForObject("SELECT status FROM lots WHERE id = ?", String.class, blocked))
                .isEqualTo("blocked");
        assertThat(jdbc.queryForList("SELECT CAST(quantity_on_hand AS CHAR) FROM lots WHERE product_id = ? "
                + "AND lot_number <> ?", String.class, product, "R-" + key)).containsOnly("3.000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE product_id = ? "
                + "AND movement_type = 'receipt_cancellation'", Integer.class, product)).isZero();
        assertThat(jdbc.queryForObject("SELECT CAST(received_base AS CHAR) FROM purchase_order_lines WHERE id = ?",
                String.class, poLine)).isEqualTo("6.000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM supplier_price_history WHERE receipt_id = ? AND cancelled",
                Integer.class, receipt)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'RECEIPT_CANCEL' "
                + "AND entity_id = ?", Integer.class, receipt)).isZero();
    }

    private void assertNothingChanged(int counter, String idem) {
        assertThat(jdbc.queryForList("SELECT lot_number FROM lots WHERE product_id = ?", String.class, product))
                .containsExactly("R-" + key);
        assertThat(jdbc.queryForObject("SELECT status FROM lots WHERE id = ?", String.class, recalled))
                .isEqualTo("recalled");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE product_id = ?", Integer.class,
                product)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM goods_receipts WHERE supplier_id = ?", Integer.class,
                supplier)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM supplier_price_history WHERE supplier_id = ?",
                Integer.class, supplier)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE idem_key = ?", Integer.class,
                idem)).isZero();
        assertThat(grCounter()).as("no GR number consumed").isEqualTo(counter);
        assertThat(jdbc.queryForMap("""
                SELECT o.status, CAST(l.received_base AS CHAR) AS received FROM purchase_orders o
                JOIN purchase_order_lines l ON l.purchase_order_id = o.id WHERE o.id = ?""", order))
                .containsEntry("status", "sent").containsEntry("received", "0.000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'LOT_BLOCK' AND entity_id IN "
                + "(SELECT id FROM lots WHERE product_id = ?)", Integer.class, product)).isZero();
    }

    private ReceiptInput input(ReceivedLine... lines) {
        return new ReceiptInput(supplier, order, null, null, false, List.of(lines));
    }

    private ReceivedLine line(String lotNumber, boolean conforming) {
        return new ReceivedLine(poLine, product, null, new BigDecimal("3"), lotNumber, EXPIRY, new BigDecimal("0.5"),
                conforming);
    }

    private int grCounter() {
        return jdbc.queryForObject("""
                SELECT COALESCE(MAX(last_number), 0) FROM document_sequences WHERE doc_key = 'GR' AND year = 2026""",
                Integer.class);
    }
}
