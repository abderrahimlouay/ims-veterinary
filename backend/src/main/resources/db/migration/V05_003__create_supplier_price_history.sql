-- Supplier price and discount history (database.md §3.3, FR-PURCH-002, BR-PURCH-008, ADR-100): per supplier and
-- product, price per base unit with 4 decimals (ADR-049, ADR-094), discount 0–100. Appended by receipts when the
-- price or discount changes, or manually; never edited.
CREATE TABLE supplier_price_history (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    supplier_id  BIGINT        NOT NULL,
    product_id   BIGINT        NOT NULL,
    unit_price   DECIMAL(14,4) NOT NULL,
    discount_pct DECIMAL(5,2)  NOT NULL DEFAULT 0,
    valid_from   DATE          NOT NULL,
    created_at   DATETIME(6)   NOT NULL,
    created_by   BIGINT        NOT NULL,
    CONSTRAINT pk_supplier_price_history PRIMARY KEY (id),
    CONSTRAINT fk_supplier_price_history_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    CONSTRAINT fk_supplier_price_history_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_supplier_price_history_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_supplier_price_history_price CHECK (unit_price >= 0),
    CONSTRAINT ck_supplier_price_history_discount CHECK (discount_pct >= 0 AND discount_pct <= 100),
    INDEX idx_supplier_price_history_latest (supplier_id, product_id, valid_from, id)
);
