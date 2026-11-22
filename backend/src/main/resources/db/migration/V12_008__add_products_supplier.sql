-- The supplier of a product, a laboratory or a wholesaler, replaces its manufacturer (ADR-180). Each manufacturer
-- still used by a product becomes a supplier of kind laboratory, unless a supplier of that name already exists
-- (names compare ignoring case and accents, ADR-100), and the product takes that supplier. Manufacturers no product
-- uses are not carried over. The manufacturer column and list are dropped by V12_009.
ALTER TABLE products
    ADD COLUMN supplier_id BIGINT NULL AFTER dosage_form_id,
    ADD CONSTRAINT fk_products_supplier_id FOREIGN KEY (supplier_id) REFERENCES suppliers (id);

INSERT INTO suppliers (name, supplier_kind, created_at, updated_at)
SELECT m.name, 'laboratory', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM manufacturers m
WHERE EXISTS (SELECT 1 FROM products p WHERE p.manufacturer_id = m.id)
  AND NOT EXISTS (SELECT 1 FROM suppliers s WHERE s.name = m.name);

UPDATE products p
    JOIN manufacturers m ON m.id = p.manufacturer_id
    JOIN suppliers s ON s.name = m.name
SET p.supplier_id = s.id;
