-- Goods receipts (database.md §3.3, FR-PURCH-005, W1b): created confirmed and numbered GR (ADR-097), for a sent or
-- partially received order or without order (ADR-096); cancelled as a whole with a reason, keeping the number
-- (ADR-103, SD-127). Never deleted.
CREATE TABLE goods_receipts (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    number            VARCHAR(30) NOT NULL,
    purchase_order_id BIGINT      NULL,
    supplier_id       BIGINT      NOT NULL,
    supplier_doc_ref  VARCHAR(60) NULL,
    notes             TEXT        NULL,
    received_at       DATETIME(6) NOT NULL,
    received_by       BIGINT      NOT NULL,
    status            VARCHAR(15) NOT NULL DEFAULT 'confirmed',
    cancel_reason     TEXT        NULL,
    cancelled_by      BIGINT      NULL,
    cancelled_at      DATETIME(6) NULL,
    CONSTRAINT pk_goods_receipts PRIMARY KEY (id),
    CONSTRAINT uq_goods_receipts_number UNIQUE (number),
    CONSTRAINT fk_goods_receipts_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders (id),
    CONSTRAINT fk_goods_receipts_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    CONSTRAINT fk_goods_receipts_received_by FOREIGN KEY (received_by) REFERENCES users (id),
    CONSTRAINT fk_goods_receipts_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES users (id),
    CONSTRAINT ck_goods_receipts_status CHECK (status IN ('confirmed', 'cancelled')),
    CONSTRAINT ck_goods_receipts_cancelled CHECK ((status = 'cancelled') = (cancelled_at IS NOT NULL)
        AND (cancelled_at IS NULL) = (cancel_reason IS NULL)
        AND (cancelled_at IS NULL) = (cancelled_by IS NULL)),
    INDEX idx_goods_receipts_supplier (supplier_id, received_at),
    INDEX idx_goods_receipts_received_at (received_at)
);
