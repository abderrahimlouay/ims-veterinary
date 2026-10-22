-- Product barcodes (database.md §3.2, FR-PROD-003, BR-PROD-002/008, BR-UNIT-005). code is compared exactly and
-- case-sensitively (utf8mb4_bin, ADR-058) and unique across all products. code_type CHECK; the per-type format is
-- checked by the service (ADR-067). unit_id NULL = base unit; otherwise a unit of the same product, enforced by the
-- composite foreign key to product_units(id, product_id) (SD-105, ADR-057).
CREATE TABLE product_barcodes (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    product_id BIGINT      NOT NULL,
    code       VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    code_type  VARCHAR(10) NOT NULL DEFAULT 'EAN13',
    unit_id    BIGINT      NULL,
    CONSTRAINT pk_product_barcodes PRIMARY KEY (id),
    CONSTRAINT uq_product_barcodes_code UNIQUE (code),
    CONSTRAINT fk_product_barcodes_product_id FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_barcodes_unit_product FOREIGN KEY (unit_id, product_id)
        REFERENCES product_units (id, product_id),
    CONSTRAINT ck_product_barcodes_code_not_blank CHECK (CHAR_LENGTH(TRIM(code)) > 0),
    CONSTRAINT ck_product_barcodes_code_type CHECK (code_type IN ('EAN13', 'CODE128', 'QR')),
    INDEX idx_product_barcodes_product (product_id)
);
