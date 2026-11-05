package com.imsvet.ims.reporting.application;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.time.BusinessCalendar;
import com.imsvet.ims.reporting.infrastructure.TraceabilityFigures;
import com.imsvet.ims.reporting.infrastructure.TraceabilityFigures.MovementFigure;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lot traceability (FR-LOT-002, BR-RPT-005): every movement of a lot with its document and party, oldest first; upstream
 * the suppliers it was received from (receipts less their cancellations, and supplier returns), downstream the
 * customers it went to (sales less their cancellations, and customer returns), anonymous counter sales included as a
 * customer without id. Opening stock and adjustments appear in the movements only.
 */
@Service
public class TraceabilityReports {

    private static final int QUANTITY_SCALE = 3;

    public record ProductRef(long id, String sku, String name, String baseUnit) {
    }

    /** {@code expired}: the expiry date is before today (BR-LOT-003); {@code status} is the stored status. */
    public record LotRef(long id, String lotNumber, LocalDate expiryDate, String status, boolean expired,
            BigDecimal quantityOnHand, ProductRef product) {
    }

    /** {@code type}: the document type ({@code goods_receipt}, {@code invoice}, {@code customer_return}, …). */
    public record DocumentRef(String type, Long id, String number) {
    }

    /** {@code type}: supplier or customer. */
    public record PartyRef(String type, Long id, String name) {
    }

    public record MovementRow(long id, Instant at, String type, BigDecimal quantity, String user, DocumentRef document,
            PartyRef party) {
    }

    /** Received = receipts − their cancellations; returned = supplier returns; base units. */
    public record SupplierRow(long id, String name, BigDecimal received, BigDecimal returned) {
    }

    /** {@code id} and {@code name} {@code null} for anonymous counter sales; {@code net} = sold − returned. */
    public record CustomerRow(Long id, String name, BigDecimal sold, BigDecimal returned, BigDecimal net) {
    }

    public record LotTraceability(LotRef lot, List<MovementRow> movements, List<SupplierRow> suppliers,
            List<CustomerRow> customers) {
    }

    private final TraceabilityFigures figures;
    private final BusinessCalendar calendar;

    public TraceabilityReports(TraceabilityFigures figures, BusinessCalendar calendar) {
        this.figures = figures;
        this.calendar = calendar;
    }

    @PreAuthorize("hasAuthority('lot.recall')")
    @Transactional(readOnly = true)
    public LotTraceability lot(long lotId) {
        List<MovementFigure> found = figures.movements(lotId);
        if (found.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Lot not found.", Map.of("id", lotId));
        }
        MovementFigure first = found.getFirst();
        LotRef lot = new LotRef(first.lotId(), first.lotNumber(), first.expiryDate(), first.lotStatus(),
                first.expiryDate().isBefore(calendar.today()), first.quantityOnHand(),
                new ProductRef(first.productId(), first.sku(), first.productName(), first.baseUnit()));
        List<MovementRow> movements = new ArrayList<>();
        Map<Long, BigDecimal[]> suppliers = new LinkedHashMap<>();
        Map<Long, String> supplierNames = new LinkedHashMap<>();
        Map<Long, BigDecimal[]> customers = new LinkedHashMap<>();
        Map<Long, String> customerNames = new LinkedHashMap<>();
        for (MovementFigure m : found) {
            movements.add(new MovementRow(m.movementId(), m.createdAt(), m.movementType(), m.quantity(), m.userName(),
                    m.documentNumber() == null ? null
                            : new DocumentRef(m.documentType(), m.documentId(), m.documentNumber()),
                    m.partyType() == null ? null : new PartyRef(m.partyType(), m.partyId(), m.partyName())));
            switch (m.movementType()) {
                case "purchase_receipt", "receipt_cancellation" -> add(suppliers, supplierNames, m, 0, m.quantity());
                case "supplier_return" -> add(suppliers, supplierNames, m, 1, m.quantity().negate());
                case "sale", "sale_cancellation" -> add(customers, customerNames, m, 0, m.quantity().negate());
                case "customer_return" -> add(customers, customerNames, m, 1, m.quantity());
                default -> {
                    // opening stock, adjustments and discards: in the movements only
                }
            }
        }
        List<SupplierRow> supplierRows = suppliers.entrySet().stream().map(e -> new SupplierRow(e.getKey(),
                supplierNames.get(e.getKey()), e.getValue()[0], e.getValue()[1])).toList();
        List<CustomerRow> customerRows = customers.entrySet().stream().map(e -> new CustomerRow(e.getKey(),
                customerNames.get(e.getKey()), e.getValue()[0], e.getValue()[1],
                e.getValue()[0].subtract(e.getValue()[1]))).toList();
        return new LotTraceability(lot, movements, supplierRows, customerRows);
    }

    private static void add(Map<Long, BigDecimal[]> totals, Map<Long, String> names, MovementFigure m, int column,
            BigDecimal quantity) {
        BigDecimal[] row = totals.computeIfAbsent(m.partyId(), id -> new BigDecimal[] {zero(), zero()});
        row[column] = row[column].add(quantity);
        names.putIfAbsent(m.partyId(), m.partyName());
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(QUANTITY_SCALE);
    }
}
