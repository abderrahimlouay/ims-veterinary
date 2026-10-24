-- Suppliers (database.md §3.3, FR-PURCH-001, ADR-100): laboratories and wholesalers, optional tax id, payment terms
-- in days. The name is unique ignoring case and accents through the default collation (SD-129). Never deleted:
-- deactivated (BR-PURCH-011). version for optimistic locking (ADR-027).
CREATE TABLE suppliers (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    name               VARCHAR(150) NOT NULL,
    supplier_kind      VARCHAR(20)  NOT NULL DEFAULT 'wholesaler',
    tax_id             VARCHAR(40)  NULL,
    phone              VARCHAR(40)  NULL,
    email              VARCHAR(150) NULL,
    address            TEXT         NULL,
    payment_terms_days SMALLINT     NOT NULL DEFAULT 0,
    is_active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_suppliers PRIMARY KEY (id),
    CONSTRAINT uq_suppliers_name UNIQUE (name),
    CONSTRAINT ck_suppliers_name_not_blank CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_suppliers_kind CHECK (supplier_kind IN ('laboratory', 'wholesaler')),
    CONSTRAINT ck_suppliers_payment_terms CHECK (payment_terms_days >= 0)
);
