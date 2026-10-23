-- Count lines (database.md §3.4, ADR-072, ADR-074, SD-120). One line per lot; a lot found during the count and
-- unknown to the system has no lot_id yet but its product, lot number, expiry and unit cost (BR-LOT-001); it is
-- created at regularisation. theoretical_qty is the balance at count opening (informational); balance_at_count and
-- watermark_movement_id are the lot balance and last movement when counted_qty was saved, and the variance is taken
-- against them. The composite foreign key keeps the line's product equal to its lot's (ADR-062).
CREATE TABLE inventory_lines (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    inventory_id           BIGINT        NOT NULL,
    product_id             BIGINT        NOT NULL,
    lot_id                 BIGINT        NULL,
    found_lot_number       VARCHAR(60)   NULL,
    found_expiry_date      DATE          NULL,
    found_unit_cost        DECIMAL(14,4) NULL,
    theoretical_qty        DECIMAL(14,3) NOT NULL DEFAULT 0,
    counted_qty            DECIMAL(14,3) NULL,
    counted_at             DATETIME(6)   NULL,
    counted_by             BIGINT        NULL,
    balance_at_count       DECIMAL(14,3) NULL,
    watermark_movement_id  BIGINT        NULL,
    variance_qty           DECIMAL(14,3) GENERATED ALWAYS AS (counted_qty - balance_at_count) STORED,
    regularized            BOOLEAN       NOT NULL DEFAULT FALSE,
    adjustment_movement_id BIGINT        NULL,
    CONSTRAINT pk_inventory_lines PRIMARY KEY (id),
    CONSTRAINT uq_inventory_lines_lot UNIQUE (inventory_id, lot_id),
    CONSTRAINT fk_inventory_lines_inventory FOREIGN KEY (inventory_id) REFERENCES inventories (id) ON DELETE CASCADE,
    CONSTRAINT fk_inventory_lines_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_inventory_lines_lot_product FOREIGN KEY (lot_id, product_id) REFERENCES lots (id, product_id),
    CONSTRAINT fk_inventory_lines_counted_by FOREIGN KEY (counted_by) REFERENCES users (id),
    CONSTRAINT fk_inventory_lines_adjustment FOREIGN KEY (adjustment_movement_id) REFERENCES stock_movements (id),
    CONSTRAINT ck_inventory_lines_found_lot CHECK (lot_id IS NOT NULL OR (found_lot_number IS NOT NULL
        AND CHAR_LENGTH(TRIM(found_lot_number)) > 0 AND found_expiry_date IS NOT NULL AND found_unit_cost IS NOT NULL)),
    CONSTRAINT ck_inventory_lines_found_cost CHECK (found_unit_cost IS NULL OR found_unit_cost >= 0),
    CONSTRAINT ck_inventory_lines_theoretical CHECK (theoretical_qty >= 0),
    CONSTRAINT ck_inventory_lines_counted CHECK (counted_qty IS NULL OR counted_qty >= 0),
    CONSTRAINT ck_inventory_lines_balance_at_count CHECK (balance_at_count IS NULL OR balance_at_count >= 0),
    CONSTRAINT ck_inventory_lines_counted_together CHECK ((counted_qty IS NULL) = (balance_at_count IS NULL))
);
