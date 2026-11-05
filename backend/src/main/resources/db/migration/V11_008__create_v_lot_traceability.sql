-- Lot traceability (FR-LOT-002, BR-RPT-005, SD-154): one row per stock movement of a lot, with the lot and its
-- product, the document behind the movement (its number and type) and the party: the supplier of a receipt or a
-- supplier return, the customer of a sale or of a customer return, whose movements name the return line (none for an anonymous counter sale). Opening stock
-- and inventory adjustments have no party. Read by reporting only (ADR-014).
CREATE SQL SECURITY INVOKER VIEW v_lot_traceability AS
SELECT l.id               AS lot_id,
       l.lot_number       AS lot_number,
       l.expiry_date      AS expiry_date,
       l.status           AS lot_status,
       l.quantity_on_hand AS quantity_on_hand,
       p.id               AS product_id,
       p.sku              AS sku,
       p.commercial_name  AS product_name,
       p.base_unit        AS base_unit,
       m.id               AS movement_id,
       m.movement_type    AS movement_type,
       m.quantity         AS quantity,
       m.created_at       AS created_at,
       u.full_name        AS user_name,
       m.reference_type   AS reference_type,
       m.reference_id     AS reference_id,
       COALESCE(gr.number, sr.number, sd.number, cr.number) AS document_number,
       CASE m.reference_type WHEN 'sales_document' THEN sd.doc_type
                             WHEN 'customer_return_line' THEN 'customer_return'
                             ELSE m.reference_type END AS document_type,
       COALESCE(cr.id, m.reference_id) AS document_id,
       CASE WHEN gr.id IS NOT NULL OR sr.id IS NOT NULL THEN 'supplier'
            WHEN COALESCE(sd.customer_id, od.customer_id) IS NOT NULL THEN 'customer' END AS party_type,
       COALESCE(gr.supplier_id, sr.supplier_id, sd.customer_id, od.customer_id) AS party_id,
       COALESCE(gs.name, ss.name, sc.name, oc.name) AS party_name
FROM stock_movements m
JOIN lots l ON l.id = m.lot_id
JOIN products p ON p.id = m.product_id
JOIN users u ON u.id = m.user_id
LEFT JOIN goods_receipts gr ON m.reference_type = 'goods_receipt' AND gr.id = m.reference_id
LEFT JOIN suppliers gs ON gs.id = gr.supplier_id
LEFT JOIN supplier_returns sr ON m.reference_type = 'supplier_return' AND sr.id = m.reference_id
LEFT JOIN suppliers ss ON ss.id = sr.supplier_id
LEFT JOIN sales_documents sd ON m.reference_type = 'sales_document' AND sd.id = m.reference_id
LEFT JOIN customers sc ON sc.id = sd.customer_id
LEFT JOIN customer_return_lines crl ON m.reference_type = 'customer_return_line' AND crl.id = m.reference_id
LEFT JOIN customer_returns cr ON cr.id = crl.return_id
LEFT JOIN sales_documents od ON od.id = cr.document_id
LEFT JOIN customers oc ON oc.id = od.customer_id;
