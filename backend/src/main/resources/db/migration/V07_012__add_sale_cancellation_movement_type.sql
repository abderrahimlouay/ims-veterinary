-- A twelfth movement type, sale_cancellation (+), returns the stock of a cancelled sales document to its lots (ADR-119,
-- BR-STOCK-005, SD-135). The applied migrations are not edited: the type and sign CHECKs are replaced.
ALTER TABLE stock_movements
    DROP CHECK ck_stock_movements_type,
    DROP CHECK ck_stock_movements_sign;

ALTER TABLE stock_movements
    ADD CONSTRAINT ck_stock_movements_type CHECK (movement_type IN ('purchase_receipt', 'sale', 'customer_return',
        'supplier_return', 'adjustment_breakage', 'adjustment_expiry', 'adjustment_theft', 'adjustment_internal_use',
        'adjustment_inventory', 'opening_stock', 'receipt_cancellation', 'sale_cancellation')),
    ADD CONSTRAINT ck_stock_movements_sign CHECK (
        (movement_type IN ('purchase_receipt', 'customer_return', 'opening_stock', 'sale_cancellation') AND quantity > 0)
        OR (movement_type IN ('sale', 'supplier_return', 'receipt_cancellation', 'adjustment_breakage',
            'adjustment_expiry', 'adjustment_theft', 'adjustment_internal_use') AND quantity < 0)
        OR movement_type = 'adjustment_inventory');
