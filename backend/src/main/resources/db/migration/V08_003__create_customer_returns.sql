-- Customer returns (database.md §3.7, W3, ADR-131, SD-141): against a confirmed invoice or receipt, numbered CR at
-- creation (ADR-097), a mandatory reason, exactly one credit note (CN) issued with it. Never edited or deleted
-- (BR-RET-007): the service has no such use case.
CREATE TABLE customer_returns (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    number         VARCHAR(30)  NOT NULL,
    document_id    BIGINT       NOT NULL,
    credit_note_id BIGINT       NOT NULL,
    reason         VARCHAR(500) NOT NULL,
    created_by     BIGINT       NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    CONSTRAINT pk_customer_returns PRIMARY KEY (id),
    CONSTRAINT uq_customer_returns_number UNIQUE (number),
    CONSTRAINT uq_customer_returns_credit_note UNIQUE (credit_note_id),
    CONSTRAINT fk_customer_returns_document FOREIGN KEY (document_id) REFERENCES sales_documents (id),
    CONSTRAINT fk_customer_returns_credit_note FOREIGN KEY (credit_note_id) REFERENCES sales_documents (id),
    CONSTRAINT fk_customer_returns_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_customer_returns_reason_not_blank CHECK (CHAR_LENGTH(TRIM(reason)) > 0),
    CONSTRAINT ck_customer_returns_distinct_documents CHECK (credit_note_id <> document_id),
    INDEX idx_customer_returns_document (document_id, created_at)
);
