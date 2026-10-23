-- Inventory count sessions (database.md §3.4, W5, FR-STOCK-006). full counts every lot with stock; cycle counts one
-- category (scope_category_id) or a product list (inventory_scope_products, V04_006) (ADR-073, SD-113).
-- At most one open full count (BR-INV-007): the generated open_full_flag is 1 only for an open full count, and unique.
CREATE TABLE inventories (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    inv_type          VARCHAR(30) NOT NULL DEFAULT 'full',
    status            VARCHAR(30) NOT NULL DEFAULT 'open',
    scope_category_id INT         NULL,
    started_by        BIGINT      NOT NULL,
    started_at        DATETIME(6) NOT NULL,
    closed_at         DATETIME(6) NULL,
    closed_by         BIGINT      NULL,
    notes             TEXT        NULL,
    version           BIGINT      NOT NULL DEFAULT 0,
    open_full_flag    TINYINT     GENERATED ALWAYS AS (IF(inv_type = 'full' AND status = 'open', 1, NULL)) STORED,
    CONSTRAINT pk_inventories PRIMARY KEY (id),
    CONSTRAINT uq_inventories_one_open_full UNIQUE (open_full_flag),
    CONSTRAINT fk_inventories_scope_category FOREIGN KEY (scope_category_id) REFERENCES therapeutic_categories (id),
    CONSTRAINT fk_inventories_started_by FOREIGN KEY (started_by) REFERENCES users (id),
    CONSTRAINT fk_inventories_closed_by FOREIGN KEY (closed_by) REFERENCES users (id),
    CONSTRAINT ck_inventories_type CHECK (inv_type IN ('full', 'cycle')),
    CONSTRAINT ck_inventories_status CHECK (status IN ('open', 'closed', 'cancelled')),
    CONSTRAINT ck_inventories_scope_only_cycle CHECK (inv_type = 'cycle' OR scope_category_id IS NULL),
    INDEX idx_inventories_status (status, started_at)
);
