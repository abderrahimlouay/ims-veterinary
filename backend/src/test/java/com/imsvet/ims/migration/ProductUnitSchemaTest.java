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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository test (R) of product_units (database.md §3.2): the database backs BR-UNIT-001 (factor > 0, name
 * unique per product ignoring case) and ADR-063 (optional unit price ≥ 0); UQ(id, product_id) exists for the
 * barcode composite foreign key (ADR-057).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ProductUnitSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    private long productId;

    @BeforeEach
    void product() {
        jdbc.update("""
                INSERT INTO products (sku, commercial_name, base_unit, is_divisible, vat_rate, created_at, updated_at)
                VALUES ('UNIT-SCHEMA', 'Solution X', 'ml', TRUE, 9.00, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""");
        productId = jdbc.queryForObject("SELECT id FROM products WHERE sku = 'UNIT-SCHEMA'", Long.class);
    }

    @Test
    void unitNameIsUniquePerProductIgnoringCase() {
        insert("Bottle", "100", null);

        assertThatThrownBy(() -> insert("bottle", "50", null)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void factorMustBePositiveAndKeepsFourDecimals() {
        assertThatThrownBy(() -> insert("Zero", "0", null)).satisfies(e -> checkViolation(e, "factor_to_base"));
        insert("Dose", "2.5005", null);

        assertThat(jdbc.queryForObject("SELECT factor_to_base FROM product_units WHERE name = 'Dose'",
                BigDecimal.class)).isEqualByComparingTo("2.5005");
    }

    @Test
    void unitPriceIsOptionalAndNotNegative() {
        insert("Box", "10", null);

        assertThatThrownBy(() -> insert("Pack", "10", "-0.01")).satisfies(e -> checkViolation(e, "sale_price"));
    }

    @Test
    void idAndProductAreUniqueTogetherForCompositeForeignKeys() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = DATABASE() AND table_name = 'product_units'
                  AND constraint_name = 'uq_product_units_id_product' AND constraint_type = 'UNIQUE'""", Integer.class);

        assertThat(count).isEqualTo(1);
    }

    private void insert(String name, String factor, String price) {
        jdbc.update("INSERT INTO product_units (product_id, name, factor_to_base, sale_price) VALUES (?, ?, ?, ?)",
                productId, name, new BigDecimal(factor), price == null ? null : new BigDecimal(price));
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
