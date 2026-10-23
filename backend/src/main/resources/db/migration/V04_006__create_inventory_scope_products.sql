-- The explicit product list of a cycle count (ADR-073, SD-113).
CREATE TABLE inventory_scope_products (
    inventory_id BIGINT NOT NULL,
    product_id   BIGINT NOT NULL,
    CONSTRAINT pk_inventory_scope_products PRIMARY KEY (inventory_id, product_id),
    CONSTRAINT fk_inventory_scope_products_inventory FOREIGN KEY (inventory_id) REFERENCES inventories (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_inventory_scope_products_product FOREIGN KEY (product_id) REFERENCES products (id)
);
