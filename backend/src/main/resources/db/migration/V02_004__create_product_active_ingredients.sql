-- Active ingredients of a product with an optional strength (FR-PROD-004, database.md §3.2). The ingredient
-- foreign key makes a used ingredient undeletable (ADR-053); the index serves the substitute search.
CREATE TABLE product_active_ingredients (
    product_id    BIGINT      NOT NULL,
    ingredient_id INT         NOT NULL,
    strength      VARCHAR(60) NULL,
    CONSTRAINT pk_product_active_ingredients PRIMARY KEY (product_id, ingredient_id),
    CONSTRAINT fk_product_active_ingredients_product_id FOREIGN KEY (product_id) REFERENCES products (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_product_active_ingredients_ingredient_id FOREIGN KEY (ingredient_id)
        REFERENCES active_ingredients (id),
    INDEX idx_product_active_ingredients_ingredient (ingredient_id)
);
