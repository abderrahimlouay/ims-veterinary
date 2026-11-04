-- Stock valuation read model (FR-RPT-002, BR-RPT-002, ADR-014; the Source view kept): one row per lot holding
-- stock, valued at its cost (weighted average per base unit, ADR-098) and at the product's sale price per base unit.
-- Every lot on hand is listed, sellable or not; the report shows the unsellable part apart.
CREATE SQL SECURITY INVOKER VIEW v_stock_valuation AS
SELECT l.id                AS lot_id,
       l.lot_number        AS lot_number,
       l.expiry_date       AS expiry_date,
       l.status            AS lot_status,
       l.product_id        AS product_id,
       p.sku               AS sku,
       p.commercial_name   AS product_name,
       p.base_unit         AS base_unit,
       p.category_id       AS category_id,
       tc.name             AS category_name,
       l.quantity_on_hand  AS quantity_base,
       ROUND(l.quantity_on_hand * l.unit_cost, 2)  AS value_at_cost,
       ROUND(l.quantity_on_hand * p.sale_price, 2) AS value_at_sale_price
FROM lots l
JOIN products p ON p.id = l.product_id
LEFT JOIN therapeutic_categories tc ON tc.id = p.category_id
WHERE l.quantity_on_hand > 0;
