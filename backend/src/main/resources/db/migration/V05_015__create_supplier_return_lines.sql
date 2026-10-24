-- Supplier return lines (database.md §3.3, BR-PURCH-009): one lot of the line's product (composite FK, SD-103), the
-- base quantity returned and the line's supplier_return movement.
CREATE TABLE supplier_return_lines (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    return_id     BIGINT        NOT NULL,
    lot_id        BIGINT        NOT NULL,
    product_id    BIGINT        NOT NULL,
    quantity_base DECIMAL(14,3) NOT NULL,
    movement_id   BIGINT        NOT NULL,
    CONSTRAINT pk_supplier_return_lines PRIMARY KEY (id),
    CONSTRAINT uq_supplier_return_lines_movement UNIQUE (movement_id),
    CONSTRAINT fk_supplier_return_lines_return FOREIGN KEY (return_id) REFERENCES supplier_returns (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_supplier_return_lines_lot_product FOREIGN KEY (lot_id, product_id) REFERENCES lots (id, product_id),
    CONSTRAINT fk_supplier_return_lines_movement FOREIGN KEY (movement_id) REFERENCES stock_movements (id),
    CONSTRAINT ck_supplier_return_lines_quantity CHECK (quantity_base > 0)
);
