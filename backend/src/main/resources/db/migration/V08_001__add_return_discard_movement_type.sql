-- A thirteenth movement type, return_discard (−), destroys returned goods right after their customer_return (+) on
-- the same lot and return line, with a mandatory reason (ADR-127, BR-STOCK-005, BR-RET-010, SD-107). The applied
-- migrations are not edited: the type and sign CHECKs are replaced, and a reason CHECK is added for the new type.
ALTER TABLE stock_movements
    DROP CHECK ck_stock_movements_type,
    DROP CHECK ck_stock_movements_sign;

ALTER TABLE stock_movements
    ADD CONSTRAINT ck_stock_movements_type CHECK (movement_type IN ('purchase_receipt', 'sale', 'customer_return',
        'supplier_return', 'adjustment_breakage', 'adjustment_expiry', 'adjustment_theft', 'adjustment_internal_use',
        'adjustment_inventory', 'opening_stock', 'receipt_cancellation', 'sale_cancellation', 'return_discard')),
    ADD CONSTRAINT ck_stock_movements_sign CHECK (
        (movement_type IN ('purchase_receipt', 'customer_return', 'opening_stock', 'sale_cancellation') AND quantity > 0)
        OR (movement_type IN ('sale', 'supplier_return', 'receipt_cancellation', 'return_discard', 'adjustment_breakage',
            'adjustment_expiry', 'adjustment_theft', 'adjustment_internal_use') AND quantity < 0)
        OR movement_type = 'adjustment_inventory'),
    ADD CONSTRAINT ck_stock_movements_return_discard_reason CHECK (movement_type <> 'return_discard'
        OR (reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason)) > 0));
