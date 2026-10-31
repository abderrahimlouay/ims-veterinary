-- Sales documents (database.md §3.7, FR-SALE-001/002): one table for quotes, delivery notes, invoices, receipts and
-- credit notes. A draft has no number; a document is numbered at confirmation (ADR-122, SD-136), and a draft that is
-- abandoned stays cancelled without one. Only an identified customer can buy on credit (BR-SALE-012); a receipt is
-- never a credit sale. cash_session_id comes with the cash sessions, cancel_reason with cancellation (ADR-119).
-- version for optimistic locking (ADR-027).
CREATE TABLE sales_documents (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    doc_type       VARCHAR(30)   NOT NULL,
    number         VARCHAR(30)   NULL,
    status         VARCHAR(30)   NOT NULL DEFAULT 'draft',
    customer_id    BIGINT        NULL,
    parent_id      BIGINT        NULL,
    is_credit_sale BOOLEAN       NOT NULL DEFAULT FALSE,
    due_date       DATE          NULL,
    issued_at      DATETIME(6)   NULL,
    total_ht       DECIMAL(14,2) NOT NULL DEFAULT 0,
    total_discount DECIMAL(14,2) NOT NULL DEFAULT 0,
    total_vat      DECIMAL(14,2) NOT NULL DEFAULT 0,
    total_ttc      DECIMAL(14,2) NOT NULL DEFAULT 0,
    notes          TEXT          NULL,
    created_by     BIGINT        NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    cancelled_by   BIGINT        NULL,
    cancelled_at   DATETIME(6)   NULL,
    version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_sales_documents PRIMARY KEY (id),
    CONSTRAINT uq_sales_documents_number UNIQUE (doc_type, number),
    CONSTRAINT fk_sales_documents_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_sales_documents_parent FOREIGN KEY (parent_id) REFERENCES sales_documents (id),
    CONSTRAINT fk_sales_documents_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_sales_documents_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES users (id),
    CONSTRAINT ck_sales_documents_type
        CHECK (doc_type IN ('quote', 'delivery_note', 'invoice', 'receipt', 'credit_note')),
    CONSTRAINT ck_sales_documents_status CHECK (status IN ('draft', 'confirmed', 'cancelled')),
    CONSTRAINT ck_sales_documents_number CHECK (status <> 'confirmed' OR number IS NOT NULL),
    CONSTRAINT ck_sales_documents_draft_number CHECK (status <> 'draft' OR number IS NULL),
    CONSTRAINT ck_sales_documents_credit_customer CHECK (is_credit_sale = FALSE OR customer_id IS NOT NULL),
    CONSTRAINT ck_sales_documents_credit_type CHECK (is_credit_sale = FALSE OR doc_type <> 'receipt'),
    CONSTRAINT ck_sales_documents_totals
        CHECK (total_ht >= 0 AND total_discount >= 0 AND total_vat >= 0 AND total_ttc >= 0),
    INDEX idx_sales_documents_customer (customer_id, issued_at),
    INDEX idx_sales_documents_issued (issued_at)
);
