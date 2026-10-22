-- Lots (database.md §2.2/§3.4, FR-LOT-*). Identity (product, lot number) with the default case- and
-- accent-insensitive collation (ADR-070, ADR-058); UQ(id, product_id) is the target of the lot/product composite
-- foreign keys of later tables (ADR-062). Stored status is only what a person decides (active, blocked, recalled);
-- expired and depleted are computed (ADR-059). Quantity >= 0 makes negative stock impossible (BR-STOCK-001).
-- No supplier_id until Phase 5 (ADR-066). Lots are created only by opening stock, inventory and receipts (ADR-064).
CREATE TABLE lots (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    product_id       BIGINT        NOT NULL,
    lot_number       VARCHAR(60)   NOT NULL,
    expiry_date      DATE          NOT NULL,
    unit_cost        DECIMAL(14,4) NOT NULL DEFAULT 0,
    quantity_on_hand DECIMAL(14,3) NOT NULL DEFAULT 0,
    status           VARCHAR(30)   NOT NULL DEFAULT 'active',
    blocked_reason   TEXT          NULL,
    blocked_by       BIGINT        NULL,
    blocked_at       DATETIME(6)   NULL,
    received_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_lots PRIMARY KEY (id),
    CONSTRAINT uq_lots_product_lot_number UNIQUE (product_id, lot_number),
    CONSTRAINT uq_lots_id_product UNIQUE (id, product_id),
    CONSTRAINT fk_lots_product_id FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_lots_blocked_by FOREIGN KEY (blocked_by) REFERENCES users (id),
    CONSTRAINT ck_lots_lot_number_not_blank CHECK (CHAR_LENGTH(TRIM(lot_number)) > 0),
    CONSTRAINT ck_lots_unit_cost_non_negative CHECK (unit_cost >= 0),
    CONSTRAINT ck_lots_quantity_on_hand_non_negative CHECK (quantity_on_hand >= 0),
    CONSTRAINT ck_lots_status CHECK (status IN ('active', 'blocked', 'recalled')),
    INDEX idx_lots_fefo (product_id, status, expiry_date),
    INDEX idx_lots_expiry (expiry_date)
);
