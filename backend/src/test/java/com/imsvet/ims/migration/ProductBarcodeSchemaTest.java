package com.imsvet.ims.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.sql.SQLException;
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
 * Repository test (R) of product_barcodes (database.md §3.2): BR-PROD-002 unique code compared exactly
 * (utf8mb4_bin, ADR-058); BR-PROD-008 type CHECK; BR-UNIT-005 a barcode's unit belongs to its product
 * (composite foreign key, ADR-057), none meaning the base unit.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ProductBarcodeSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    private long productA;
    private long productB;
    private long unitOfB;

    @BeforeEach
    void products() {
        productA = product("BC-A");
        productB = product("BC-B");
        jdbc.update("INSERT INTO product_units (product_id, name, factor_to_base) VALUES (?, 'Box', ?)", productB,
                new BigDecimal("10"));
        unitOfB = jdbc.queryForObject("SELECT id FROM product_units WHERE product_id = ?", Long.class, productB);
    }

    @Test
    void aCodeIsUniqueAcrossProductsAndComparedExactly() {
        insert(productA, "abc-1", "CODE128", null);

        assertThatThrownBy(() -> insert(productB, "abc-1", "CODE128", null)).isInstanceOf(DuplicateKeyException.class);
        insert(productB, "ABC-1", "CODE128", null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_barcodes WHERE code = 'abc-1'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void theTypeIsOneOfTheKnownTypes() {
        assertThatThrownBy(() -> insert(productA, "X1", "UPC", null)).satisfies(e -> checkViolation(e, "code_type"));
    }

    @Test
    void aBarcodeUnitMustBelongToTheSameProduct() {
        insert(productB, "4006381333931", "EAN13", unitOfB);
        insert(productA, "5901234123457", "EAN13", null);

        assertThatThrownBy(() -> insert(productA, "9780306406157", "EAN13", unitOfB))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long product(String sku) {
        jdbc.update("""
                INSERT INTO products (sku, commercial_name, base_unit, is_divisible, vat_rate, created_at, updated_at)
                VALUES (?, 'Name', 'tablet', FALSE, 9.00, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", sku);
        return jdbc.queryForObject("SELECT id FROM products WHERE sku = ?", Long.class, sku);
    }

    private void insert(long productId, String code, String type, Long unitId) {
        jdbc.update("INSERT INTO product_barcodes (product_id, code, code_type, unit_id) VALUES (?, ?, ?, ?)",
                productId, code, type, unitId);
    }

    /** MySQL reports a violated CHECK as error 3819 naming the constraint. */
    private static void checkViolation(Throwable thrown, String column) {
        assertThat(((DataAccessException) thrown).getMostSpecificCause())
                .isInstanceOfSatisfying(SQLException.class, sql -> {
                    assertThat(sql.getErrorCode()).isEqualTo(3819);
                    assertThat(sql.getMessage()).contains(column);
                });
    }
}
