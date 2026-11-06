-- One row per confirmed invoice, receipt or credit note (ADR-147, ADR-158, SD-157): its date, customer and signed
-- revenue (a credit note negative), for the reports by day or month, which need no line. A document's total_ht is the
-- sum of its lines' line_ht (SaleTotals, ADR-016), so the figures equal v_sales_margin's (parity test,
-- SalesReportsTest); read from the documents' report index alone, without the 2.25 million lines.
CREATE SQL SECURITY INVOKER VIEW v_sales_documents AS
SELECT d.id              AS document_id,
       d.doc_type        AS doc_type,
       d.issued_at       AS issued_at,
       d.customer_id     AS customer_id,
       c.customer_type   AS customer_type,
       CASE WHEN d.doc_type = 'credit_note' THEN -d.total_ht ELSE d.total_ht END AS revenue_ht
FROM sales_documents d
LEFT JOIN customers c ON c.id = d.customer_id
WHERE d.status = 'confirmed'
  AND d.doc_type IN ('invoice', 'receipt', 'credit_note');
