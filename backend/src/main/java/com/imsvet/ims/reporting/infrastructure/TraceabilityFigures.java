package com.imsvet.ims.reporting.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads {@code v_lot_traceability} (ADR-014, BR-RPT-005): every movement of one lot, oldest first. */
@Repository
public class TraceabilityFigures {

    /** The lot and its product repeat on each row; the document and party are {@code null} where there is none. */
    public record MovementFigure(long lotId, String lotNumber, LocalDate expiryDate, String lotStatus,
            BigDecimal quantityOnHand, long productId, String sku, String productName, String baseUnit, long movementId,
            String movementType, BigDecimal quantity, Instant createdAt, String userName, String referenceType,
            Long documentId, String documentNumber, String documentType, String partyType, Long partyId,
            String partyName) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    TraceabilityFigures(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Empty for an unknown lot: every lot has at least the movement that created it. */
    public List<MovementFigure> movements(long lotId) {
        return jdbc.query("""
                SELECT lot_id, lot_number, expiry_date, lot_status, quantity_on_hand, product_id, sku, product_name,
                       base_unit, movement_id, movement_type, quantity, created_at, user_name, reference_type,
                       document_id, document_number, document_type, party_type, party_id, party_name
                FROM v_lot_traceability
                WHERE lot_id = :lotId
                ORDER BY movement_id
                """, new MapSqlParameterSource("lotId", lotId), (rs, i) -> new MovementFigure(rs.getLong("lot_id"),
                rs.getString("lot_number"), rs.getObject("expiry_date", LocalDate.class), rs.getString("lot_status"),
                rs.getBigDecimal("quantity_on_hand"), rs.getLong("product_id"), rs.getString("sku"),
                rs.getString("product_name"), rs.getString("base_unit"), rs.getLong("movement_id"),
                rs.getString("movement_type"), rs.getBigDecimal("quantity"),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getString("user_name"),
                rs.getString("reference_type"), rs.getObject("document_id", Long.class),
                rs.getString("document_number"), rs.getString("document_type"), rs.getString("party_type"),
                rs.getObject("party_id", Long.class), rs.getString("party_name")));
    }
}
