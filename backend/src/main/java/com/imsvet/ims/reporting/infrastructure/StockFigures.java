package com.imsvet.ims.reporting.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads {@code v_stock_valuation} (ADR-014, BR-RPT-002): the stock on hand now, by product or category, at cost and at
 * sale price, with the part that cannot be sold (a blocked or recalled lot, or one expired on {@code today}).
 */
@Repository
public class StockFigures {

    /** {@code id} and {@code name} are {@code null} for products without a category; {@code code} is a product's SKU. */
    public record ValuationFigure(Long id, String code, String name, String baseUnit, BigDecimal quantityBase,
            BigDecimal valueAtCost, BigDecimal valueAtSalePrice, BigDecimal unsellableQuantityBase,
            BigDecimal unsellableAtCost, BigDecimal unsellableAtSalePrice) {
    }

    private static final String UNSELLABLE = "(lot_status <> 'active' OR expiry_date < :today)";
    private static final String SUMS = """
                   SUM(quantity_base) AS quantity_base, SUM(value_at_cost) AS value_at_cost,
                   SUM(value_at_sale_price) AS value_at_sale_price,
                   SUM(CASE WHEN %1$s THEN quantity_base ELSE 0 END) AS unsellable_quantity_base,
                   SUM(CASE WHEN %1$s THEN value_at_cost ELSE 0 END) AS unsellable_at_cost,
                   SUM(CASE WHEN %1$s THEN value_at_sale_price ELSE 0 END) AS unsellable_at_sale_price
            """.formatted(UNSELLABLE);

    private final NamedParameterJdbcTemplate jdbc;

    StockFigures(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ValuationFigure> byProduct(LocalDate today) {
        return jdbc.query("""
                SELECT product_id AS id, sku AS code, product_name AS name, base_unit,
                """ + SUMS + """
                FROM v_stock_valuation
                GROUP BY product_id, sku, product_name, base_unit""", Map.of("today", today),
                (rs, i) -> figure(rs, true));
    }

    public List<ValuationFigure> byCategory(LocalDate today) {
        return jdbc.query("""
                SELECT category_id AS id, category_name AS name,
                """ + SUMS + """
                FROM v_stock_valuation
                GROUP BY category_id, category_name""", Map.of("today", today), (rs, i) -> figure(rs, false));
    }

    private static ValuationFigure figure(ResultSet rs, boolean product) throws SQLException {
        long id = rs.getLong("id");
        Long key = rs.wasNull() ? null : id;
        return new ValuationFigure(key, product ? rs.getString("code") : null, rs.getString("name"),
                product ? rs.getString("base_unit") : null, product ? rs.getBigDecimal("quantity_base") : null,
                rs.getBigDecimal("value_at_cost"), rs.getBigDecimal("value_at_sale_price"),
                product ? rs.getBigDecimal("unsellable_quantity_base") : null, rs.getBigDecimal("unsellable_at_cost"),
                rs.getBigDecimal("unsellable_at_sale_price"));
    }
}
