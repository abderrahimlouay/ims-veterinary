-- v_customer_receivables rewritten with the same rows and amounts (ADR-148, ADR-158, SD-158): the credit notes, the
-- allocations of payments not reversed and the allocations of each payment are summed once per table and joined,
-- instead of three subqueries per invoice or payment. At the target volume (135,000 credit invoices, 108,000
-- payments over five years) the receivables report took up to 5.4 s; FinanceReportsTest checks the balances against
-- the Account tab as before.
CREATE OR REPLACE SQL SECURITY INVOKER VIEW v_customer_receivables AS
SELECT c.id AS customer_id, c.name AS customer_name, c.customer_type, c.phone, c.wilaya,
       'invoice' AS item_type, d.id AS item_id, d.number, d.due_date,
       d.total_ttc - COALESCE(n.credited, 0) - COALESCE(a.paid, 0) AS amount
FROM sales_documents d
JOIN customers c ON c.id = d.customer_id
LEFT JOIN (SELECT parent_id, SUM(total_ttc) AS credited
           FROM sales_documents
           WHERE doc_type = 'credit_note' AND status = 'confirmed' AND parent_id IS NOT NULL
           GROUP BY parent_id) n ON n.parent_id = d.id
LEFT JOIN (SELECT al.invoice_id, SUM(al.amount) AS paid
           FROM payment_allocations al
           LEFT JOIN customer_payments r ON r.reverses_payment_id = al.payment_id
           WHERE r.id IS NULL
           GROUP BY al.invoice_id) a ON a.invoice_id = d.id
WHERE d.doc_type = 'invoice' AND d.status = 'confirmed' AND d.is_credit_sale
UNION ALL
SELECT c.id, c.name, c.customer_type, c.phone, c.wilaya,
       'advance', p.id, p.reference, NULL,
       p.amount - COALESCE(a.allocated, 0)
FROM customer_payments p
JOIN customers c ON c.id = p.customer_id
LEFT JOIN (SELECT payment_id, SUM(amount) AS allocated FROM payment_allocations GROUP BY payment_id) a
       ON a.payment_id = p.id
LEFT JOIN customer_payments r ON r.reverses_payment_id = p.id
WHERE p.reverses_payment_id IS NULL AND r.id IS NULL
UNION ALL
SELECT c.id, c.name, c.customer_type, c.phone, c.wilaya,
       'delivery_note', d.id, d.number, d.due_date, d.total_ttc
FROM sales_documents d
JOIN customers c ON c.id = d.customer_id
LEFT JOIN sales_documents i ON i.parent_id = d.id AND i.doc_type = 'invoice' AND i.status = 'confirmed'
WHERE d.doc_type = 'delivery_note' AND d.status = 'confirmed' AND d.is_credit_sale AND i.id IS NULL;
