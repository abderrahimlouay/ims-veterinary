-- The session a sale with a cash part is linked to (BR-SALE-013, ADR-118); none for other sales.
ALTER TABLE sales_documents
    ADD COLUMN cash_session_id BIGINT NULL AFTER due_date,
    ADD CONSTRAINT fk_sales_documents_cash_session FOREIGN KEY (cash_session_id) REFERENCES cash_sessions (id);
