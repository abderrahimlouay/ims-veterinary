-- Document number counters (database.md §3.3, ADR-011, ADR-097): one row per (key, business year), incremented
-- under row lock inside the transaction that creates the document, so a rollback also rolls the counter back.
CREATE TABLE document_sequences (
    doc_key    VARCHAR(30) NOT NULL,
    year       SMALLINT    NOT NULL,
    last_number INT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_document_sequences PRIMARY KEY (doc_key, year),
    CONSTRAINT ck_document_sequences_key_not_blank CHECK (CHAR_LENGTH(TRIM(doc_key)) > 0),
    CONSTRAINT ck_document_sequences_last_number CHECK (last_number >= 0)
);
