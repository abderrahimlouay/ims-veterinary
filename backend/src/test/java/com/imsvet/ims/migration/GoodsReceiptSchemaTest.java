package com.imsvet.ims.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.TestLots;
import java.sql.SQLException;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository test (R) of V05_008…011: a cancelled receipt has its reason, user and date (ADR-103); a line names a
 * lot of its own product (composite FK, SD-103) and one movement; the lot's previous state is present exactly when
 * the receipt did not create the lot (ADR-106, SD-131); only an entry appended by a receipt can be marked cancelled
 * (SD-132).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class GoodsReceiptSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    private long receipt;
    private long supplier;
    private long product;
    private long lot;
    private long movement;
    private long otherLot;

    @BeforeEach
    void receipt() {
        TestLots lots = new TestLots(jdbc);
        product = lots.product("GRS-1", "Schema receipt");
        lot = lots.lot(product, "GRS-L1", LocalDate.of(2027, 5, 31), "5");
        movement = jdbc.queryForObject("SELECT id FROM stock_movements WHERE lot_id = ?", Long.class, lot);
        otherLot = lots.lot(lots.product("GRS-2", "Other"), "GRS-L2", LocalDate.of(2027, 5, 31), "0");
        long user = lots.fixtureUser();
        jdbc.update("INSERT INTO suppliers (name, created_at, updated_at) VALUES ('Schema GR', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        supplier = jdbc.queryForObject("SELECT id FROM suppliers WHERE name = 'Schema GR'", Long.class);
        jdbc.update("""
                INSERT INTO goods_receipts (number, supplier_id, received_at, received_by)
                VALUES ('GR-TEST-000001', ?, UTC_TIMESTAMP(6), ?)""", supplier, user);
        receipt = jdbc.queryForObject("SELECT id FROM goods_receipts WHERE number = 'GR-TEST-000001'", Long.class);
    }

    @Test
    void aCancelledReceiptHasItsReasonUserAndDate() {
        assertThat(jdbc.queryForObject("SELECT status FROM goods_receipts WHERE id = ?", String.class, receipt))
                .isEqualTo("confirmed");
        checkViolation(catchThrowable(() -> jdbc.update("UPDATE goods_receipts SET status = 'cancelled' WHERE id = ?",
                receipt)), "ck_goods_receipts_cancelled");
        checkViolation(catchThrowable(() -> jdbc.update("""
                UPDATE goods_receipts SET status = 'cancelled', cancel_reason = 'Wrong supplier',
                       cancelled_at = UTC_TIMESTAMP(6) WHERE id = ?""", receipt)), "ck_goods_receipts_cancelled");
        checkViolation(catchThrowable(() -> jdbc.update("UPDATE goods_receipts SET status = 'draft' WHERE id = ?",
                receipt)), "ck_goods_receipts_status");
    }

    @Test
    void aLineNamesALotOfItsProductAndOneMovement() {
        line(lot, movement, false, "0.5000", "active");

        assertThatThrownBy(() -> line(otherLot, movement, false, "0.5000", "active"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> line(lot, movement, false, "0.5000", "active"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void theLotsPreviousStateIsGivenExactlyWhenTheReceiptDidNotCreateIt() {
        checkViolation(catchThrowable(() -> line(lot, movement, true, "0.5000", null)),
                "ck_goods_receipt_lines_lot_before");
        checkViolation(catchThrowable(() -> line(lot, movement, false, null, null)),
                "ck_goods_receipt_lines_lot_before");
        checkViolation(catchThrowable(() -> line(lot, movement, false, "0.5000", "expired")),
                "ck_goods_receipt_lines_lot_status_before");
        line(lot, movement, true, null, null);
    }

    @Test
    void onlyAnEntryAppendedByAReceiptIsMarkedCancelled() {
        long user = new TestLots(jdbc).fixtureUser();
        checkViolation(catchThrowable(() -> price(null, true, user)), "ck_supplier_price_history_cancelled");
        price(receipt, true, user);
        price(null, false, user);
    }

    private void line(long lotId, long movementId, boolean created, String costBefore, String statusBefore) {
        jdbc.update("""
                INSERT INTO goods_receipt_lines (receipt_id, product_id, lot_id, quantity_base, unit_cost, movement_id,
                                                 lot_created, lot_cost_before, lot_status_before)
                VALUES (?, ?, ?, 5, 0.5, ?, ?, ?, ?)""", receipt, product, lotId, movementId, created, costBefore,
                statusBefore);
    }

    private void price(Long receiptId, boolean cancelled, long user) {
        jdbc.update("""
                INSERT INTO supplier_price_history (supplier_id, product_id, unit_price, valid_from, created_at,
                                                    created_by, receipt_id, cancelled)
                VALUES (?, ?, 0.5, '2026-10-20', UTC_TIMESTAMP(6), ?, ?, ?)""", supplier, product, user, receiptId,
                cancelled);
    }

    /** MySQL reports a violated CHECK as error 3819 naming the constraint. */
    private static void checkViolation(Throwable thrown, String constraint) {
        assertThat(((DataAccessException) thrown).getMostSpecificCause())
                .isInstanceOfSatisfying(SQLException.class, sql -> {
                    assertThat(sql.getErrorCode()).isEqualTo(3819);
                    assertThat(sql.getMessage()).contains(constraint);
                });
    }
}
