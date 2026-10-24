-- Idempotency keys (database.md §3.4, ADR-104, SD-128): one row per user, endpoint and client key, claimed in the
-- transaction of the document it protects (before the document exists, so a parallel duplicate waits on the unique
-- key) and completed with the document's id in that same transaction; a rollback removes both. Kept, no purge yet.
CREATE TABLE idempotency_keys (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    user_id       BIGINT      NOT NULL,
    endpoint      VARCHAR(80) NOT NULL,
    idem_key      CHAR(36)    NOT NULL,
    request_hash  CHAR(64)    NOT NULL,
    resource_type VARCHAR(40) NOT NULL,
    resource_id   BIGINT      NULL,
    created_at    DATETIME(6) NOT NULL,
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_keys_user_endpoint_key UNIQUE (user_id, endpoint, idem_key),
    CONSTRAINT fk_idempotency_keys_user_id FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_idempotency_keys_endpoint_not_blank CHECK (CHAR_LENGTH(TRIM(endpoint)) > 0),
    CONSTRAINT ck_idempotency_keys_request_hash CHECK (CHAR_LENGTH(request_hash) = 64)
);
