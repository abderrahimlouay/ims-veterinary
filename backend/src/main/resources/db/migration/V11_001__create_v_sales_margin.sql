-- Revenue and margin read model (ADR-014, ADR-147, BR-RPT-001; replaces the Source view, DR-007): one row per line
-- of a confirmed invoice, receipt or credit note, the credit note's amounts negative so that a sum is net of credit
-- notes. Delivery notes count only through their invoice; quotes and cancelled documents never. Cost of sales is the
-- line's cost snapshot. SQL SECURITY INVOKER: read with the application account's own rights.
CREATE SQL SECURITY INVOKER VIEW v_sales_margin AS
SELECT d.id                AS document_id,
       d.doc_type          AS doc_type,
       d.number            AS number,
       d.issued_at         AS issued_at,
       d.customer_id       AS customer_id,
       c.name              AS customer_name,
       c.customer_type     AS customer_type,
       c.wilaya            AS wilaya,
       c.commune           AS commune,
       l.product_id        AS product_id,
       p.sku               AS sku,
       p.commercial_name   AS product_name,
       p.category_id       AS category_id,
       tc.name             AS category_name,
       CASE WHEN d.doc_type = 'credit_note' THEN -l.quantity_base ELSE l.quantity_base END AS quantity_base,
       CASE WHEN d.doc_type = 'credit_note' THEN -l.line_ht ELSE l.line_ht END AS revenue_ht,
       CASE WHEN d.doc_type = 'credit_note' THEN -ROUND(l.quantity_base * l.unit_cost, 2)
            ELSE ROUND(l.quantity_base * l.unit_cost, 2) END AS cost
FROM sales_document_lines l
JOIN sales_documents d ON d.id = l.document_id
JOIN products p ON p.id = l.product_id
LEFT JOIN therapeutic_categories tc ON tc.id = p.category_id
LEFT JOIN customers c ON c.id = d.customer_id
WHERE d.status = 'confirmed'
  AND d.doc_type IN ('invoice', 'receipt', 'credit_note');
