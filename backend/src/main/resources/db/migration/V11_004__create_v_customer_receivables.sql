-- What customers owe, item by item, for the receivables ageing report (ADR-148, SD-151); read by reporting only
-- (ADR-014). Same definition as the balance of ADR-118: a confirmed credit invoice less its confirmed credit notes and
-- the allocations of payments not reversed; the unallocated part of a payment on account not reversed (an advance,
-- ADR-134); and, apart, a confirmed credit delivery note that no confirmed invoice carries (ADR-117). Counter payments
-- are allocated in full to their sale, so they never leave an advance.
CREATE SQL SECURITY INVOKER VIEW v_customer_receivables AS
SELECT c.id AS customer_id, c.name AS customer_name, c.customer_type, c.phone, c.wilaya,
       'invoice' AS item_type, d.id AS item_id, d.number, d.due_date,
       d.total_ttc
           - COALESCE((SELECT SUM(n.total_ttc) FROM sales_documents n
                       WHERE n.parent_id = d.id AND n.doc_type = 'credit_note' AND n.status = 'confirmed'), 0)
           - COALESCE((SELECT SUM(a.amount) FROM payment_allocations a
                       WHERE a.invoice_id = d.id
                         AND NOT EXISTS (SELECT 1 FROM customer_payments r WHERE r.reverses_payment_id = a.payment_id)),
                      0) AS amount
FROM sales_documents d
JOIN customers c ON c.id = d.customer_id
WHERE d.doc_type = 'invoice' AND d.status = 'confirmed' AND d.is_credit_sale
UNION ALL
SELECT c.id, c.name, c.customer_type, c.phone, c.wilaya,
       'advance', p.id, p.reference, NULL,
       p.amount - COALESCE((SELECT SUM(a.amount) FROM payment_allocations a WHERE a.payment_id = p.id), 0)
FROM customer_payments p
JOIN customers c ON c.id = p.customer_id
WHERE p.reverses_payment_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM customer_payments r WHERE r.reverses_payment_id = p.id)
UNION ALL
SELECT c.id, c.name, c.customer_type, c.phone, c.wilaya,
       'delivery_note', d.id, d.number, d.due_date, d.total_ttc
FROM sales_documents d
JOIN customers c ON c.id = d.customer_id
WHERE d.doc_type = 'delivery_note' AND d.status = 'confirmed' AND d.is_credit_sale
  AND NOT EXISTS (SELECT 1 FROM sales_documents i
                  WHERE i.parent_id = d.id AND i.doc_type = 'invoice' AND i.status = 'confirmed');
