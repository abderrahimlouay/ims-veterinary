-- ADR-180: products name their supplier (V12_008); the manufacturer column and the manufacturers list go.
ALTER TABLE products
    DROP FOREIGN KEY fk_products_manufacturer_id,
    DROP COLUMN manufacturer_id;

DROP TABLE manufacturers;
