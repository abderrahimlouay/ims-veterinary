-- Supplier invoices and credit notes (database.md §3.7, ADR-133, SD-144): the supplier's own number, unique per
-- supplier (cancelled ones included, BR-PAY-008); an optional confirmed receipt; a credit note may name the supplier
-- return it settles (SD-109). Never edited: a wrong entry is cancelled with its reason, by whom and when, all together
-- (BR-PAY-015).
CREATE TABLE supplier_invoices (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    supplier_id        BIGINT        NOT NULL,
    invoice_number     VARCHAR(60)   NOT NULL,
    receipt_id         BIGINT        NULL,
    supplier_return_id BIGINT        NULL,
    invoice_date       DATE          NOT NULL,
    due_date           DATE          NOT NULL,
    total_ttc          DECIMAL(14,2) NOT NULL,
    is_credit_note     BOOLEAN       NOT NULL DEFAULT FALSE,
    status             VARCHAR(30)   NOT NULL DEFAULT 'recorded',
    cancel_reason      VARCHAR(500)  NULL,
    cancelled_by       BIGINT        NULL,
    cancelled_at       DATETIME(6)   NULL,
    created_by         BIGINT        NOT NULL,
    created_at         DATETIME(6)   NOT NULL,
    CONSTRAINT pk_supplier_invoices PRIMARY KEY (id),
    CONSTRAINT uq_supplier_invoices_number UNIQUE (supplier_id, invoice_number),
    CONSTRAINT fk_supplier_invoices_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    CONSTRAINT fk_supplier_invoices_receipt FOREIGN KEY (receipt_id) REFERENCES goods_receipts (id),
    CONSTRAINT fk_supplier_invoices_return FOREIGN KEY (supplier_return_id) REFERENCES supplier_returns (id),
    CONSTRAINT fk_supplier_invoices_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES users (id),
    CONSTRAINT fk_supplier_invoices_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_supplier_invoices_total CHECK (total_ttc >= 0),
    CONSTRAINT ck_supplier_invoices_status CHECK (status IN ('recorded', 'cancelled')),
    CONSTRAINT ck_supplier_invoices_cancelled CHECK ((status = 'cancelled') = (cancelled_at IS NOT NULL)
        AND (cancelled_at IS NULL) = (cancelled_by IS NULL) AND (cancelled_at IS NULL) = (cancel_reason IS NULL)),
    CONSTRAINT ck_supplier_invoices_return CHECK (supplier_return_id IS NULL OR is_credit_note),
    INDEX idx_supplier_invoices_due (supplier_id, due_date)
);
