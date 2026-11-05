-- What is open with each supplier, item by item, for the payables report (ADR-148, SD-152); read by reporting only
-- (ADR-014). Same definition as ADR-132 and ADR-133: a recorded invoice less its credit-note applications and the
-- allocations of payments not reversed; a recorded credit note less its applications; both never below zero; the
-- unallocated part of a payment not reversed (an advance). Cancelled documents and reversed payments are left out.
CREATE SQL SECURITY INVOKER VIEW v_supplier_open_items AS
SELECT s.id AS supplier_id, s.name AS supplier_name,
       IF(i.is_credit_note, 'credit_note', 'invoice') AS item_type, i.id AS item_id, i.invoice_number AS number,
       i.invoice_date, i.due_date, i.total_ttc,
       GREATEST(i.total_ttc - IF(i.is_credit_note,
           COALESCE((SELECT SUM(a.amount) FROM supplier_credit_allocations a WHERE a.credit_note_id = i.id), 0),
           COALESCE((SELECT SUM(a.amount) FROM supplier_credit_allocations a WHERE a.invoice_id = i.id), 0)
               + COALESCE((SELECT SUM(a.amount) FROM supplier_payment_allocations a
                           WHERE a.invoice_id = i.id
                             AND NOT EXISTS (SELECT 1 FROM supplier_payments r
                                             WHERE r.reverses_payment_id = a.payment_id)), 0)), 0) AS open_amount
FROM supplier_invoices i
JOIN suppliers s ON s.id = i.supplier_id
WHERE i.status = 'recorded'
UNION ALL
SELECT s.id, s.name, 'advance', p.id, p.reference, NULL, NULL, p.amount,
       p.amount - COALESCE((SELECT SUM(a.amount) FROM supplier_payment_allocations a WHERE a.payment_id = p.id), 0)
FROM supplier_payments p
JOIN suppliers s ON s.id = p.supplier_id
WHERE p.reverses_payment_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM supplier_payments r WHERE r.reverses_payment_id = p.id);
