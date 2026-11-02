-- Customer return lines (database.md §3.7, BR-RET-001/002/003/010, ADR-127, ADR-129, SD-141): one per returned sale
-- line, with the line's lot of the same product (composite FK, SD-103), the base quantity, whether the goods are sealed,
-- the disposition and the credit-note line that credits it. A discard has a reason code and a restock none; "other"
-- needs a note. No cascade: lines are never deleted. Movements reference the line (reference_type customer_return_line).
CREATE TABLE customer_return_lines (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    return_id           BIGINT        NOT NULL,
    sale_line_id        BIGINT        NOT NULL,
    lot_id              BIGINT        NOT NULL,
    product_id          BIGINT        NOT NULL,
    quantity_base       DECIMAL(14,3) NOT NULL,
    sealed              BOOLEAN       NOT NULL,
    disposition         VARCHAR(30)   NOT NULL,
    discard_reason      VARCHAR(30)   NULL,
    discard_note        VARCHAR(500)  NULL,
    credit_note_line_id BIGINT        NOT NULL,
    CONSTRAINT pk_customer_return_lines PRIMARY KEY (id),
    CONSTRAINT uq_customer_return_lines_sale_line UNIQUE (return_id, sale_line_id),
    CONSTRAINT uq_customer_return_lines_credit_note_line UNIQUE (credit_note_line_id),
    CONSTRAINT fk_customer_return_lines_return FOREIGN KEY (return_id) REFERENCES customer_returns (id),
    CONSTRAINT fk_customer_return_lines_sale_line FOREIGN KEY (sale_line_id) REFERENCES sales_document_lines (id),
    CONSTRAINT fk_customer_return_lines_lot_product FOREIGN KEY (lot_id, product_id) REFERENCES lots (id, product_id),
    CONSTRAINT fk_customer_return_lines_credit_note_line FOREIGN KEY (credit_note_line_id)
        REFERENCES sales_document_lines (id),
    CONSTRAINT ck_customer_return_lines_quantity CHECK (quantity_base > 0),
    CONSTRAINT ck_customer_return_lines_disposition CHECK (disposition IN ('restock', 'discard')),
    CONSTRAINT ck_customer_return_lines_discard_reason CHECK (discard_reason IS NULL OR discard_reason IN ('opened',
        'packaging_damaged', 'expired', 'recalled', 'cold_chain_doubt', 'other')),
    CONSTRAINT ck_customer_return_lines_reason_when_discard
        CHECK ((disposition = 'discard') = (discard_reason IS NOT NULL)),
    CONSTRAINT ck_customer_return_lines_note CHECK ((discard_note IS NULL OR discard_reason IS NOT NULL)
        AND (discard_reason <> 'other' OR (discard_note IS NOT NULL AND CHAR_LENGTH(TRIM(discard_note)) > 0))),
    INDEX idx_customer_return_lines_sale_line (sale_line_id)
);
