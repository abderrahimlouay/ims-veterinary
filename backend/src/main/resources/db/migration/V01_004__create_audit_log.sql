-- Audit trail (database.md §3.1, BR-AUDIT-002). Written by services in the same transaction
-- as the audited action (BR-AUDIT-003); old/new values as JSON (SD-004); IPv6-sized address (SD-005).
CREATE TABLE audit_log (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NULL,
    action     VARCHAR(60) NOT NULL,
    entity     VARCHAR(60) NOT NULL,
    entity_id  BIGINT      NULL,
    old_values JSON        NULL,
    new_values JSON        NULL,
    ip_address VARCHAR(45) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    CONSTRAINT fk_audit_log_user_id FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_audit_log_action_not_blank CHECK (CHAR_LENGTH(TRIM(action)) > 0),
    CONSTRAINT ck_audit_log_entity_not_blank CHECK (CHAR_LENGTH(TRIM(entity)) > 0),
    INDEX idx_audit_log_entity (entity, entity_id),
    INDEX idx_audit_log_user_created (user_id, created_at DESC)
);
