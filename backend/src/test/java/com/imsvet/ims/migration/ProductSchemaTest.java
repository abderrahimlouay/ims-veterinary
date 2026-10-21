package com.imsvet.ims.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository test (R) of the products table (database.md §3.2): the database is the last line of defence
 * for BR-PROD-001, BR-PROD-003 and BR-PROD-005; prices per base unit keep 4 decimals (ADR-049).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ProductSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void skuIsUniqueIgnoringCaseAndAccents() {
        insert("AMX-10", "Amoxival", "tablet", "0", null);

        assertThatThrownBy(() -> insert("amx-10", "Other", "tablet", "0", null))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void baseUnitIsMandatory() {
        assertThatThrownBy(() -> insert("SKU-1", "Name", " ", "0", null)).satisfies(e -> checkViolation(e, "base_unit"));
    }

    @Test
    void thresholdsAndPricesCannotBeNegativeAndMaxIsAtLeastMin() {
        assertThatThrownBy(() -> insert("SKU-2", "Name", "ml", "-1", null)).satisfies(e -> checkViolation(e, "min_stock"));
        assertThatThrownBy(() -> insert("SKU-3", "Name", "ml", "5", "4")).satisfies(e -> checkViolation(e, "max_stock"));
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO products (sku, commercial_name, base_unit, is_divisible, vat_rate, sale_price, created_at,
                                      updated_at)
                VALUES ('SKU-4', 'Name', 'ml', TRUE, 9.00, -0.0001, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))"""))
                .satisfies(e -> checkViolation(e, "sale_price"));
    }

    @Test
    void pricesPerBaseUnitKeepFourDecimals() {
        jdbc.update("""
                INSERT INTO products (sku, commercial_name, base_unit, is_divisible, vat_rate, sale_price, created_at,
                                      updated_at)
                VALUES ('SKU-5', 'Name', 'tablet', FALSE, 9.00, 0.0125, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""");

        assertThat(jdbc.queryForObject("SELECT sale_price FROM products WHERE sku = 'SKU-5'", BigDecimal.class))
                .isEqualByComparingTo("0.0125");
    }

    private void insert(String sku, String name, String baseUnit, String minStock, String maxStock) {
        jdbc.update("""
                INSERT INTO products (sku, commercial_name, base_unit, is_divisible, vat_rate, min_stock, max_stock,
                                      created_at, updated_at)
                VALUES (?, ?, ?, TRUE, 9.00, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                sku, name, baseUnit, new BigDecimal(minStock), maxStock == null ? null : new BigDecimal(maxStock));
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
