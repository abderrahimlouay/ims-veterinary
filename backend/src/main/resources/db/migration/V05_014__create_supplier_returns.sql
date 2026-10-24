-- Supplier returns (database.md §3.3, FR-PURCH-006, W4, ADR-101): numbered SR at creation (ADR-097), a mandatory
-- reason; stock only in Phase 5 (the supplier credit is Phase 9, OD-016). Never edited or deleted.
CREATE TABLE supplier_returns (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    number      VARCHAR(30) NOT NULL,
    supplier_id BIGINT      NOT NULL,
    reason      TEXT        NOT NULL,
    created_by  BIGINT      NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    CONSTRAINT pk_supplier_returns PRIMARY KEY (id),
    CONSTRAINT uq_supplier_returns_number UNIQUE (number),
    CONSTRAINT fk_supplier_returns_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    CONSTRAINT fk_supplier_returns_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_supplier_returns_reason_not_blank CHECK (CHAR_LENGTH(TRIM(reason)) > 0),
    INDEX idx_supplier_returns_supplier (supplier_id, created_at)
);
