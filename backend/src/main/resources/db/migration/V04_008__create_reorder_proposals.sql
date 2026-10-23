-- Reorder proposals (database.md §3.4, W10, BR-STOCK-010, ADR-076…079). At most one pending proposal per product:
-- the generated pending_product_id is the product id only while pending, and unique (SD-112). A dismissed proposal
-- is suppressed (no new proposal for the product) until a generation sees its sellable quantity above the reorder
-- point (ADR-079). supplier_id arrives in Phase 5 (SD-121).
CREATE TABLE reorder_proposals (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    product_id         BIGINT        NOT NULL,
    current_qty        DECIMAL(14,3) NOT NULL,
    suggested_qty      DECIMAL(14,3) NOT NULL,
    status             VARCHAR(15)   NOT NULL DEFAULT 'pending',
    suppressed         BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    dismissed_at       DATETIME(6)   NULL,
    dismissed_by       BIGINT        NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    pending_product_id BIGINT        GENERATED ALWAYS AS (IF(status = 'pending', product_id, NULL)) STORED,
    CONSTRAINT pk_reorder_proposals PRIMARY KEY (id),
    CONSTRAINT uq_reorder_proposals_one_pending UNIQUE (pending_product_id),
    CONSTRAINT fk_reorder_proposals_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_reorder_proposals_dismissed_by FOREIGN KEY (dismissed_by) REFERENCES users (id),
    CONSTRAINT ck_reorder_proposals_status CHECK (status IN ('pending', 'converted', 'dismissed')),
    CONSTRAINT ck_reorder_proposals_current CHECK (current_qty >= 0),
    CONSTRAINT ck_reorder_proposals_suggested CHECK (suggested_qty > 0),
    CONSTRAINT ck_reorder_proposals_dismissed CHECK ((status = 'dismissed') = (dismissed_at IS NOT NULL)
        AND (dismissed_at IS NULL) = (dismissed_by IS NULL)),
    CONSTRAINT ck_reorder_proposals_suppressed CHECK (suppressed = FALSE OR status = 'dismissed'),
    INDEX idx_reorder_proposals_status (status, id),
    INDEX idx_reorder_proposals_suppressed (product_id, suppressed)
);
