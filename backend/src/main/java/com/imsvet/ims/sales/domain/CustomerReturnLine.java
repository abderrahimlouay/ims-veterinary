package com.imsvet.ims.sales.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * One returned sale line (BR-RET-001/002/003/010): its lot, base quantity, whether the goods were sealed, the
 * disposition, the discard reason and note, and the credit-note line that credits it. Never changed or removed.
 */
@Entity
@Table(name = "customer_return_lines")
public class CustomerReturnLine {

    @Converter
    static class DispositionConverter implements AttributeConverter<ReturnDisposition, String> {
        @Override
        public String convertToDatabaseColumn(ReturnDisposition disposition) {
            return disposition == null ? null : disposition.code();
        }

        @Override
        public ReturnDisposition convertToEntityAttribute(String code) {
            return code == null ? null : ReturnDisposition.fromCode(code).orElseThrow();
        }
    }

    @Converter
    static class ReasonConverter implements AttributeConverter<DiscardReason, String> {
        @Override
        public String convertToDatabaseColumn(DiscardReason reason) {
            return reason == null ? null : reason.code();
        }

        @Override
        public DiscardReason convertToEntityAttribute(String code) {
            return code == null ? null : DiscardReason.fromCode(code).orElseThrow();
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "return_id", nullable = false, updatable = false)
    private Long returnId;

    @Column(name = "sale_line_id", nullable = false, updatable = false)
    private Long saleLineId;

    @Column(name = "lot_id", nullable = false, updatable = false)
    private Long lotId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "quantity_base", nullable = false, updatable = false, precision = 14, scale = 3)
    private BigDecimal quantityBase;

    @Column(nullable = false, updatable = false)
    private boolean sealed;

    @Convert(converter = DispositionConverter.class)
    @Column(nullable = false, updatable = false, length = 30)
    private ReturnDisposition disposition;

    @Convert(converter = ReasonConverter.class)
    @Column(name = "discard_reason", updatable = false, length = 30)
    private DiscardReason discardReason;

    @Column(name = "discard_note", updatable = false, length = 500)
    private String discardNote;

    @Column(name = "credit_note_line_id", nullable = false, updatable = false)
    private Long creditNoteLineId;

    protected CustomerReturnLine() {
    }

    public CustomerReturnLine(Long returnId, Long saleLineId, Long lotId, Long productId, BigDecimal quantityBase,
            boolean sealed, ReturnDisposition disposition, DiscardReason discardReason, String discardNote,
            Long creditNoteLineId) {
        this.returnId = returnId;
        this.saleLineId = saleLineId;
        this.lotId = lotId;
        this.productId = productId;
        this.quantityBase = quantityBase;
        this.sealed = sealed;
        this.disposition = disposition;
        this.discardReason = discardReason;
        this.discardNote = discardNote;
        this.creditNoteLineId = creditNoteLineId;
    }

    public Long id() {
        return id;
    }

    public Long returnId() {
        return returnId;
    }

    public Long saleLineId() {
        return saleLineId;
    }

    public Long lotId() {
        return lotId;
    }

    public Long productId() {
        return productId;
    }

    public BigDecimal quantityBase() {
        return quantityBase;
    }

    public boolean sealed() {
        return sealed;
    }

    public ReturnDisposition disposition() {
        return disposition;
    }

    public DiscardReason discardReason() {
        return discardReason;
    }

    public String discardNote() {
        return discardNote;
    }

    public Long creditNoteLineId() {
        return creditNoteLineId;
    }
}
