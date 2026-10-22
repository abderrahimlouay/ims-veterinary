-- Packaging units (database.md §2.3/§3.2, FR-PROD-006/007, BR-UNIT-001). The base unit has no row (unit_id NULL
-- on a line means the base unit). factor_to_base: base units in one of this unit, > 0 with 4 decimals; whole for a
-- non-divisible product (ADR-065, enforced by the service). sale_price NULL = derived from the product price
-- (ADR-063). Name unique per product, ignoring case and accents (default collation). UQ(id, product_id) is the
-- target of the barcode composite foreign key (ADR-057).
CREATE TABLE product_units (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    product_id     BIGINT        NOT NULL,
    name           VARCHAR(40)   NOT NULL,
    factor_to_base DECIMAL(14,4) NOT NULL,
    sale_price     DECIMAL(14,2) NULL,
    is_sellable    BOOLEAN       NOT NULL DEFAULT TRUE,
    version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_product_units PRIMARY KEY (id),
    CONSTRAINT uq_product_units_product_name UNIQUE (product_id, name),
    CONSTRAINT uq_product_units_id_product UNIQUE (id, product_id),
    CONSTRAINT fk_product_units_product_id FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE,
    CONSTRAINT ck_product_units_name_not_blank CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_product_units_factor_to_base_positive CHECK (factor_to_base > 0),
    CONSTRAINT ck_product_units_sale_price_non_negative CHECK (sale_price IS NULL OR sale_price >= 0)
);
