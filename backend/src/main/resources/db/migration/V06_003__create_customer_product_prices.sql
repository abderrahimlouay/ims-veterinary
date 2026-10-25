-- Customer prices (database.md §3.6, FR-SALE-010, ADR-111, SD-134): one price per customer × product, per base unit,
-- > 0 with 4 decimals; set, changed and removed one at a time, each change audited (PRICE_CHANGE). RESTRICT foreign
-- keys: neither customers nor products are ever deleted. version for optimistic locking (ADR-027).
CREATE TABLE customer_product_prices (
    customer_id BIGINT        NOT NULL,
    product_id  BIGINT        NOT NULL,
    unit_price  DECIMAL(14,4) NOT NULL,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    version     BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_customer_product_prices PRIMARY KEY (customer_id, product_id),
    CONSTRAINT fk_customer_product_prices_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_customer_product_prices_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_customer_product_prices_price CHECK (unit_price > 0)
);
