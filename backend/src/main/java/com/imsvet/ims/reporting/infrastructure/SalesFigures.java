package com.imsvet.ims.reporting.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the report views of sales (ADR-014, ADR-147): {@code v_sales_margin}, signed so that a sum is net of credit
 * notes, {@code v_sales_documents} (one row per document, for the reports by date) and
 * {@code v_uninvoiced_delivery_notes}. A period is a half-open range of instants {@code [from, to)}; the
 * columns hold UTC (ADR-010).
 */
@Repository
public class SalesFigures {

    /** One document's net revenue; the caller buckets it by business date. */
    public record DocumentFigure(Instant issuedAt, boolean creditNote, BigDecimal revenueHt) {
    }

    /**
     * Sales statistics by customer or zone: {@code id} is the customer id ({@code null} for counter sales without a
     * customer), {@code code} the wilaya when grouped by commune; {@code documents} counts invoices and receipts, not
     * credit notes.
     */
    public record StatisticFigure(Long id, String code, String name, long documents, BigDecimal revenueHt) {
    }

    /** {@code id} and {@code name} are {@code null} for products without a category. */
    public record GroupFigure(Long id, String code, String name, BigDecimal quantityBase, BigDecimal revenueHt,
            BigDecimal cost) {
    }

    public record Uninvoiced(long count, BigDecimal totalHt) {
    }

    private static final String STATISTICS = """
                   COUNT(DISTINCT CASE WHEN doc_type <> 'credit_note' THEN document_id END) AS documents,
                   SUM(revenue_ht) AS revenue_ht
            FROM v_sales_margin
            WHERE issued_at >= :from AND issued_at < :to
              AND (:customerType IS NULL OR customer_type = :customerType)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    SalesFigures(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code customerType} {@code null} = every sale, counter sales without a customer included. Reads
     * {@code v_sales_documents}, one row per document (ADR-158): its total equals the sum of its lines.
     */
    public List<DocumentFigure> byDocument(Instant from, Instant to, String customerType) {
        return jdbc.query("""
                SELECT issued_at, doc_type, revenue_ht
                FROM v_sales_documents
                WHERE issued_at >= :from AND issued_at < :to
                  AND (:customerType IS NULL OR customer_type = :customerType)
                ORDER BY issued_at, document_id""", range(from, to, customerType),
                (rs, i) -> new DocumentFigure(rs.getObject("issued_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        "credit_note".equals(rs.getString("doc_type")), rs.getBigDecimal("revenue_ht")));
    }

    public List<StatisticFigure> byCustomer(Instant from, Instant to, String customerType) {
        return jdbc.query("""
                SELECT customer_id AS id, NULL AS code, customer_name AS name,
                """ + STATISTICS + """
                GROUP BY customer_id, customer_name""", range(from, to, customerType), (rs, i) -> statistic(rs));
    }

    public List<StatisticFigure> byWilaya(Instant from, Instant to, String customerType) {
        return jdbc.query("""
                SELECT NULL AS id, NULL AS code, wilaya AS name,
                """ + STATISTICS + """
                GROUP BY wilaya""", range(from, to, customerType), (rs, i) -> statistic(rs));
    }

    public List<StatisticFigure> byCommune(Instant from, Instant to, String customerType) {
        return jdbc.query("""
                SELECT NULL AS id, wilaya AS code, commune AS name,
                """ + STATISTICS + """
                GROUP BY wilaya, commune""", range(from, to, customerType), (rs, i) -> statistic(rs));
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

    private static StatisticFigure statistic(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        Long key = rs.wasNull() ? null : id;
        return new StatisticFigure(key, rs.getString("code"), rs.getString("name"), rs.getLong("documents"),
                rs.getBigDecimal("revenue_ht"));
    }

    private static Map<String, Object> range(Instant from, Instant to, String customerType) {
        Map<String, Object> params = new HashMap<>(range(from, to));
        params.put("customerType", customerType);
        return params;
    }

    private static Map<String, Object> range(Instant from, Instant to) {
        return Map.of("from", LocalDateTime.ofInstant(from, ZoneOffset.UTC), "to",
                LocalDateTime.ofInstant(to, ZoneOffset.UTC));
    }
}
