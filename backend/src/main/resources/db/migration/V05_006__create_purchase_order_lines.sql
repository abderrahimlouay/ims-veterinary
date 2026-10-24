-- Purchase-order lines (database.md §3.3): quantity in the ordered unit and in base units, price per ordered unit
-- with 4 decimals (ADR-094, SD-126), VAT rate prefilled from the product (ADR-105). line_ht and line_vat are
-- generated with the service's formula, rounded per line (ADR-093, ADR-016 parity). One line per product
-- (ADR-105); the unit belongs to the product (composite FK). received_base never exceeds the order (ADR-096).
CREATE TABLE purchase_order_lines (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    purchase_order_id BIGINT        NOT NULL,
    product_id        BIGINT        NOT NULL,
    unit_id           BIGINT        NULL,
    quantity          DECIMAL(14,3) NOT NULL,
    quantity_base     DECIMAL(14,3) NOT NULL,
    received_base     DECIMAL(14,3) NOT NULL DEFAULT 0,
    unit_price        DECIMAL(14,4) NOT NULL,
    discount_pct      DECIMAL(5,2)  NOT NULL DEFAULT 0,
    vat_rate          DECIMAL(5,2)  NOT NULL DEFAULT 0,
    line_ht           DECIMAL(14,2) GENERATED ALWAYS AS (ROUND(quantity * unit_price * (1 - discount_pct / 100), 2))
                      STORED,
    line_vat          DECIMAL(14,2) GENERATED ALWAYS AS (ROUND(ROUND(quantity * unit_price * (1 - discount_pct / 100), 2)
                      * vat_rate / 100, 2)) STORED,
    CONSTRAINT pk_purchase_order_lines PRIMARY KEY (id),
    CONSTRAINT uq_purchase_order_lines_product UNIQUE (purchase_order_id, product_id),
    CONSTRAINT fk_purchase_order_lines_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_purchase_order_lines_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_purchase_order_lines_unit FOREIGN KEY (unit_id, product_id) REFERENCES product_units (id, product_id),
    CONSTRAINT ck_purchase_order_lines_quantity CHECK (quantity > 0 AND quantity_base > 0),
    CONSTRAINT ck_purchase_order_lines_received CHECK (received_base >= 0 AND received_base <= quantity_base),
    CONSTRAINT ck_purchase_order_lines_price CHECK (unit_price >= 0),
    CONSTRAINT ck_purchase_order_lines_discount CHECK (discount_pct >= 0 AND discount_pct <= 100),
    CONSTRAINT ck_purchase_order_lines_vat CHECK (vat_rate >= 0 AND vat_rate <= 100)
);
