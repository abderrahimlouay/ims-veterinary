package com.imsvet.ims.reporting.infrastructure;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the report views of finance (ADR-014, ADR-148): {@code v_customer_receivables} and
 * {@code v_supplier_open_items}, item by item, the caller ageing and totalling them; {@code v_cash_sessions}.
 */
@Repository
public class FinanceFigures {

    /** {@code type}: invoice, advance or delivery_note; {@code dueDate} only for documents. */
    public record ReceivableItem(long customerId, String customerName, String phone, String wilaya, String type,
            LocalDate dueDate, BigDecimal amount) {
    }

    /** {@code type}: invoice, credit_note or advance; the dates and number only for documents. */
    public record SupplierItem(long supplierId, String supplierName, String type, long itemId, String number,
            LocalDate invoiceDate, LocalDate dueDate, BigDecimal totalTtc, BigDecimal open) {
    }

    /** A closed cash session; {@code closerName} {@code null} only if no closer was recorded. */
    public record SessionFigure(long sessionId, long cashierId, String cashierName, Long closedBy, String closerName,
            Instant openedAt, Instant closedAt, BigDecimal openingAmount, BigDecimal expectedAmount,
            BigDecimal countedAmount, BigDecimal variance) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    FinanceFigures(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code customerType} {@code null} = every customer. Items worth nothing are left out. */
    public List<ReceivableItem> receivables(String customerType) {
        return jdbc.query("""
                SELECT customer_id, customer_name, phone, wilaya, item_type, due_date, amount
                FROM v_customer_receivables
                WHERE amount <> 0 AND (:customerType IS NULL OR customer_type = :customerType)
                """, new MapSqlParameterSource("customerType", customerType), (rs, i) -> new ReceivableItem(
                rs.getLong("customer_id"), rs.getString("customer_name"), rs.getString("phone"), rs.getString("wilaya"),
                rs.getString("item_type"), date(rs.getDate("due_date")), rs.getBigDecimal("amount")));
    }

    /** {@code supplierId} {@code null} = every supplier. Items with nothing open are left out. */
    public List<SupplierItem> supplierItems(Long supplierId) {
        return jdbc.query("""
                SELECT supplier_id, supplier_name, item_type, item_id, number, invoice_date, due_date, total_ttc,
                       open_amount
                FROM v_supplier_open_items
                WHERE open_amount > 0 AND (:supplierId IS NULL OR supplier_id = :supplierId)
                ORDER BY due_date, number, item_id
                """, new MapSqlParameterSource("supplierId", supplierId),
                (rs, i) -> new SupplierItem(rs.getLong("supplier_id"), rs.getString("supplier_name"),
                        rs.getString("item_type"), rs.getLong("item_id"), rs.getString("number"),
                        date(rs.getDate("invoice_date")), date(rs.getDate("due_date")), rs.getBigDecimal("total_ttc"),
                        rs.getBigDecimal("open_amount")));
    }

    /** Sessions closed in {@code [from, to)} (UTC instants, ADR-010), by closing time; {@code cashierId} optional. */
    public List<SessionFigure> closedSessions(Instant from, Instant to, Long cashierId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", LocalDateTime.ofInstant(from, ZoneOffset.UTC))
                .addValue("to", LocalDateTime.ofInstant(to, ZoneOffset.UTC)).addValue("cashierId", cashierId);
        return jdbc.query("""
                SELECT session_id, cashier_id, cashier_name, closed_by, closer_name, opened_at, closed_at,
                       opening_amount, expected_amount, counted_amount, variance_amount
                FROM v_cash_sessions
                WHERE closed_at >= :from AND closed_at < :to AND (:cashierId IS NULL OR cashier_id = :cashierId)
                ORDER BY closed_at, session_id
                """, params, (rs, i) -> new SessionFigure(rs.getLong("session_id"), rs.getLong("cashier_id"),
                rs.getString("cashier_name"), rs.getObject("closed_by", Long.class), rs.getString("closer_name"),
                instant(rs.getObject("opened_at", LocalDateTime.class)),
                instant(rs.getObject("closed_at", LocalDateTime.class)), rs.getBigDecimal("opening_amount"),
                rs.getBigDecimal("expected_amount"), rs.getBigDecimal("counted_amount"),
                rs.getBigDecimal("variance_amount")));
    }

    private static Instant instant(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDate date(Date value) {
        return value == null ? null : value.toLocalDate();
    }
}
