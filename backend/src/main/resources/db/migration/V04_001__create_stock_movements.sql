-- Stock ledger (database.md §3.4, ADR-028, FR-STOCK-001/002). Signed quantity in the base unit; the lot balance
-- equals the sum of its movements (BR-STOCK-007). Only StockService inserts (ADR-088); rows are never changed or
-- removed (BR-STOCK-003: triggers in V04_002, application account SELECT/INSERT only, ADR-087).
-- The ten decided types (ADR-081); sign per type (BR-STOCK-005); a reason for every adjustment (BR-STOCK-006).
-- The composite foreign key keeps the movement's product equal to its lot's (ADR-062, BR-LOT-007).
CREATE TABLE stock_movements (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    lot_id         BIGINT        NOT NULL,
    product_id     BIGINT        NOT NULL,
    movement_type  VARCHAR(30)   NOT NULL,
    quantity       DECIMAL(14,3) NOT NULL,
    reference_type VARCHAR(40)   NULL,
    reference_id   BIGINT        NULL,
    reason         TEXT          NULL,
    user_id        BIGINT        NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    CONSTRAINT pk_stock_movements PRIMARY KEY (id),
    CONSTRAINT fk_stock_movements_lot_product FOREIGN KEY (lot_id, product_id) REFERENCES lots (id, product_id),
    CONSTRAINT fk_stock_movements_product_id FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_stock_movements_user_id FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_stock_movements_type CHECK (movement_type IN ('purchase_receipt', 'sale', 'customer_return',
        'supplier_return', 'adjustment_breakage', 'adjustment_expiry', 'adjustment_theft', 'adjustment_internal_use',
        'adjustment_inventory', 'opening_stock')),
    CONSTRAINT ck_stock_movements_quantity_not_zero CHECK (quantity <> 0),
    CONSTRAINT ck_stock_movements_sign CHECK (
        (movement_type IN ('purchase_receipt', 'customer_return', 'opening_stock') AND quantity > 0)
        OR (movement_type IN ('sale', 'supplier_return', 'adjustment_breakage', 'adjustment_expiry',
            'adjustment_theft', 'adjustment_internal_use') AND quantity < 0)
        OR movement_type = 'adjustment_inventory'),
    CONSTRAINT ck_stock_movements_adjustment_reason CHECK (movement_type NOT LIKE 'adjustment\_%'
        OR (reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason)) > 0)),
    INDEX idx_stock_movements_product_created (product_id, created_at),
    INDEX idx_stock_movements_lot_id (lot_id, id),
    INDEX idx_stock_movements_reference (reference_type, reference_id)
);
