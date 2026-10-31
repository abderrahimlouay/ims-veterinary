-- Sales document lines (database.md §3.7): one lot per line (BR-LOT-006), assigned by confirmation at the latest
-- (BR-SALE-005); quantity in the sold unit and in base units; price per sold unit with 4 decimals (ADR-123, SD-137);
-- unit cost and VAT rate snapshotted (BR-SALE-008/009). Amounts are generated with the service's formula, rounded
-- per line (ADR-093, ADR-115, ADR-016 parity). The unit belongs to the product (composite FK).
CREATE TABLE sales_document_lines (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    document_id       BIGINT        NOT NULL,
    product_id        BIGINT        NOT NULL,
    lot_id            BIGINT        NULL,
    unit_id           BIGINT        NULL,
    target_species_id INT           NULL,
    quantity          DECIMAL(14,3) NOT NULL,
    quantity_base     DECIMAL(14,3) NOT NULL,
    unit_price        DECIMAL(14,4) NOT NULL,
    unit_cost         DECIMAL(14,4) NOT NULL DEFAULT 0,
    discount_pct      DECIMAL(5,2)  NOT NULL DEFAULT 0,
    vat_rate          DECIMAL(5,2)  NOT NULL DEFAULT 0,
    line_ht           DECIMAL(14,2) GENERATED ALWAYS AS (ROUND(quantity * unit_price * (1 - discount_pct / 100), 2))
                      STORED,
    line_vat          DECIMAL(14,2) GENERATED ALWAYS AS (ROUND(ROUND(quantity * unit_price * (1 - discount_pct / 100), 2)
                      * vat_rate / 100, 2)) STORED,
    line_ttc          DECIMAL(14,2) GENERATED ALWAYS AS (line_ht + line_vat) STORED,
    CONSTRAINT pk_sales_document_lines PRIMARY KEY (id),
    CONSTRAINT fk_sales_document_lines_document FOREIGN KEY (document_id) REFERENCES sales_documents (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_sales_document_lines_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_sales_document_lines_lot FOREIGN KEY (lot_id) REFERENCES lots (id),
    CONSTRAINT fk_sales_document_lines_unit FOREIGN KEY (unit_id, product_id) REFERENCES product_units (id, product_id),
    CONSTRAINT fk_sales_document_lines_species FOREIGN KEY (target_species_id) REFERENCES animal_species (id),
    CONSTRAINT ck_sales_document_lines_quantity CHECK (quantity > 0 AND quantity_base > 0),
    CONSTRAINT ck_sales_document_lines_price CHECK (unit_price >= 0 AND unit_cost >= 0),
    CONSTRAINT ck_sales_document_lines_discount CHECK (discount_pct >= 0 AND discount_pct <= 100),
    CONSTRAINT ck_sales_document_lines_vat CHECK (vat_rate >= 0 AND vat_rate <= 100),
    INDEX idx_sales_document_lines_lot (lot_id)
);
