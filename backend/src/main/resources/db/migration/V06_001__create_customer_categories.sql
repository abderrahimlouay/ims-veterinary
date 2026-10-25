-- Customer categories (database.md §3.6, FR-SALE-005, ADR-112): a default discount 0–100 %. The name is unique
-- ignoring case and accents through the default collation; renamed, never deleted. version for optimistic locking
-- (ADR-027).
CREATE TABLE customer_categories (
    id           INT          NOT NULL AUTO_INCREMENT,
    name         VARCHAR(80)  NOT NULL,
    discount_pct DECIMAL(5,2) NOT NULL DEFAULT 0,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_customer_categories PRIMARY KEY (id),
    CONSTRAINT uq_customer_categories_name UNIQUE (name),
    CONSTRAINT ck_customer_categories_name_not_blank CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_customer_categories_discount CHECK (discount_pct >= 0 AND discount_pct <= 100)
);
