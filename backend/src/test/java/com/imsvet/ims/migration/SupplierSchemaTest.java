package com.imsvet.ims.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.support.TestLots;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
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
 * Repository test (R) of V05_001…003: supplier name unique ignoring case and accents (SD-129), kind and payment
 * terms CHECKs, lots.supplier_id with its foreign key (SD-117), price-history bounds (BR-PURCH-008).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SupplierSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theNameIsUniqueIgnoringCaseAndAccents() {
        insertSupplier("Pharmacie Générale", "wholesaler", 0);

        assertThatThrownBy(() -> insertSupplier("PHARMACIE generale", "wholesaler", 0))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void kindAndTermsAreChecked() {
        checkViolation(catchThrowable(() -> insertSupplier("Kind", "retailer", 0)), "ck_suppliers_kind");
        checkViolation(catchThrowable(() -> insertSupplier("Terms", "laboratory", -1)), "ck_suppliers_payment_terms");
        checkViolation(catchThrowable(() -> insertSupplier(" ", "laboratory", 0)), "ck_suppliers_name_not_blank");
    }

    @Test
    void aLotMayReferenceAnExistingSupplierOnly() {
        TestLots lots = new TestLots(jdbc);
        long lot = lots.lot(lots.product("SCH-SUP", "Schema"), "L-SUP", LocalDate.of(2027, 1, 31), "0");
        long supplier = insertSupplier("Lot Supplier", "laboratory", 0);

        jdbc.update("UPDATE lots SET supplier_id = ? WHERE id = ?", supplier, lot);
        assertThat(jdbc.queryForObject("SELECT supplier_id FROM lots WHERE id = ?", Long.class, lot)).isEqualTo(supplier);
        assertThatThrownBy(() -> jdbc.update("UPDATE lots SET supplier_id = 999999 WHERE id = ?", lot))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void priceHistoryBoundsAreChecked() {
        TestLots lots = new TestLots(jdbc);
        long product = lots.product("SCH-PRICE", "Schema price");
        long supplier = insertSupplier("Price Bounds", "wholesaler", 0);
        long user = lots.fixtureUser();

        checkViolation(catchThrowable(() -> insertPrice(supplier, product, "-0.0001", "0", user)),
                "ck_supplier_price_history_price");
        checkViolation(catchThrowable(() -> insertPrice(supplier, product, "1", "100.01", user)),
                "ck_supplier_price_history_discount");
    }

    private long insertSupplier(String name, String kind, int terms) {
        jdbc.update("""
                INSERT INTO suppliers (name, supplier_kind, payment_terms_days, created_at, updated_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", name, kind, terms);
        return jdbc.queryForObject("SELECT MAX(id) FROM suppliers", Long.class);
    }

    private void insertPrice(long supplier, long product, String price, String discount, long user) {
        jdbc.update("""
                INSERT INTO supplier_price_history (supplier_id, product_id, unit_price, discount_pct, valid_from,
                                                    created_at, created_by)
                VALUES (?, ?, ?, ?, '2026-10-20', UTC_TIMESTAMP(6), ?)""", supplier, product,
                new BigDecimal(price), new BigDecimal(discount), user);
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
