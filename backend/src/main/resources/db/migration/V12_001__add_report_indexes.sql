-- Report indexes (ADR-158, SD-156), measured on the target volume (450,000 documents, 2.25 million lines;
-- VolumePerformanceTest): the report views select confirmed documents of some types over a period, then read their
-- lines. Without these the documents were read by a full table scan and a year's revenue by month took 6.3 s;
-- with them a range scan on the documents and an index-only read of the lines bring it to 3.1 s (bound 5 s).
CREATE INDEX idx_sales_documents_report ON sales_documents (status, doc_type, issued_at, customer_id);

CREATE INDEX idx_sales_document_lines_report ON sales_document_lines (document_id, product_id, quantity_base,
    unit_cost, line_ht);
