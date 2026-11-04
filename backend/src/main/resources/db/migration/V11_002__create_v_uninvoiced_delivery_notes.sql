-- Confirmed delivery notes without a confirmed invoice (ADR-147): shown apart from revenue, never in it.
CREATE SQL SECURITY INVOKER VIEW v_uninvoiced_delivery_notes AS
SELECT d.id          AS document_id,
       d.number      AS number,
       d.issued_at   AS issued_at,
       d.customer_id AS customer_id,
       d.total_ht    AS total_ht
FROM sales_documents d
WHERE d.doc_type = 'delivery_note'
  AND d.status = 'confirmed'
  AND NOT EXISTS (SELECT 1 FROM sales_documents i
                  WHERE i.parent_id = d.id AND i.doc_type = 'invoice' AND i.status = 'confirmed');
