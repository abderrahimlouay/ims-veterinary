-- Goods receipt lines (database.md §3.3, BR-PURCH-005): one lot per line (lot number and expiry live on the lot,
-- BR-LOT-001), base quantity, cost per base unit kept per line (ADR-098), conformity (ADR-095), and the line's
-- purchase_receipt movement, used to find later movements when cancelling (ADR-103, SD-127).
CREATE TABLE goods_receipt_lines (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    receipt_id    BIGINT        NOT NULL,
    po_line_id    BIGINT        NULL,
    product_id    BIGINT        NOT NULL,
    lot_id        BIGINT        NOT NULL,
    quantity_base DECIMAL(14,3) NOT NULL,
    unit_cost     DECIMAL(14,4) NOT NULL,
    is_conforming BOOLEAN       NOT NULL DEFAULT TRUE,
    movement_id   BIGINT        NOT NULL,
    CONSTRAINT pk_goods_receipt_lines PRIMARY KEY (id),
    CONSTRAINT uq_goods_receipt_lines_movement UNIQUE (movement_id),
    CONSTRAINT fk_goods_receipt_lines_receipt FOREIGN KEY (receipt_id) REFERENCES goods_receipts (id) ON DELETE CASCADE,
    CONSTRAINT fk_goods_receipt_lines_po_line FOREIGN KEY (po_line_id) REFERENCES purchase_order_lines (id),
    CONSTRAINT fk_goods_receipt_lines_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_goods_receipt_lines_lot_product FOREIGN KEY (lot_id, product_id) REFERENCES lots (id, product_id),
    CONSTRAINT fk_goods_receipt_lines_movement FOREIGN KEY (movement_id) REFERENCES stock_movements (id),
    CONSTRAINT ck_goods_receipt_lines_quantity CHECK (quantity_base > 0),
    CONSTRAINT ck_goods_receipt_lines_unit_cost CHECK (unit_cost >= 0)
);
