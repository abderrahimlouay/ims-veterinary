package com.imsvet.ims.reporting.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the report views of sales (ADR-014, ADR-147): {@code v_sales_margin}, signed so that a sum is net of credit
 * notes, and {@code v_uninvoiced_delivery_notes}. A period is a half-open range of instants {@code [from, to)}; the
 * columns hold UTC (ADR-010).
 */
@Repository
public class SalesFigures {

    /** One document's net revenue and cost; the caller buckets it by business date. */
    public record DocumentFigure(Instant issuedAt, BigDecimal revenueHt, BigDecimal cost) {
    }

    /** {@code id} and {@code name} are {@code null} for products without a category. */
    public record GroupFigure(Long id, String code, String name, BigDecimal quantityBase, BigDecimal revenueHt,
            BigDecimal cost) {
    }

    public record Uninvoiced(long count, BigDecimal totalHt) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    SalesFigures(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<DocumentFigure> byDocument(Instant from, Instant to) {
        return jdbc.query("""
                SELECT issued_at, SUM(revenue_ht) AS revenue_ht, SUM(cost) AS cost
                FROM v_sales_margin
                WHERE issued_at >= :from AND issued_at < :to
                GROUP BY document_id, issued_at
                ORDER BY issued_at, document_id""", range(from, to),
                (rs, i) -> new DocumentFigure(rs.getObject("issued_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getBigDecimal("revenue_ht"),
                        rs.getBigDecimal("cost")));
    }

    public List<GroupFigure> byProduct(Instant from, Instant to) {
        return jdbc.query("""
                SELECT product_id AS id, sku AS code, product_name AS name, SUM(quantity_base) AS quantity_base,
                       SUM(revenue_ht) AS revenue_ht, SUM(cost) AS cost
                FROM v_sales_margin
                WHERE issued_at >= :from AND issued_at < :to
                GROUP BY product_id, sku, product_name""", range(from, to), (rs, i) -> group(rs, true));
    }

    public List<GroupFigure> byCategory(Instant from, Instant to) {
        return jdbc.query("""
                SELECT category_id AS id, category_name AS name, SUM(revenue_ht) AS revenue_ht, SUM(cost) AS cost
                FROM v_sales_margin
                WHERE issued_at >= :from AND issued_at < :to
                GROUP BY category_id, category_name""", range(from, to), (rs, i) -> group(rs, false));
    }

    public Uninvoiced uninvoiced(Instant from, Instant to) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS documents, COALESCE(SUM(total_ht), 0) AS total_ht
                FROM v_uninvoiced_delivery_notes
                WHERE issued_at >= :from AND issued_at < :to""", range(from, to),
                (rs, i) -> new Uninvoiced(rs.getLong("documents"), rs.getBigDecimal("total_ht")));
    }

    private static GroupFigure group(ResultSet rs, boolean product) throws SQLException {
        long id = rs.getLong("id");
        Long key = rs.wasNull() ? null : id;
        return new GroupFigure(key, product ? rs.getString("code") : null, rs.getString("name"),
                product ? rs.getBigDecimal("quantity_base") : null, rs.getBigDecimal("revenue_ht"),
                rs.getBigDecimal("cost"));
    }

    private static Map<String, Object> range(Instant from, Instant to) {
        return Map.of("from", LocalDateTime.ofInstant(from, ZoneOffset.UTC), "to",
                LocalDateTime.ofInstant(to, ZoneOffset.UTC));
    }
}
