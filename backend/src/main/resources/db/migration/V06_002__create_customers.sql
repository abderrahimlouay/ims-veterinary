-- Customers (database.md §3.6, FR-SALE-004, ADR-108…110, SD-133, SD-134): breeders, veterinarians and others,
-- a category, a follow-up veterinarian (an active customer of type veterinarian, BR-CUST-001), payment terms, a
-- credit limit (NULL = no limit, 0 = cash only; a new customer starts at 0). Duplicates are refused by two unique
-- keys on normalised values (name + phone key; tax id), a missing value never conflicting (ADR-110). Never deleted
-- (ADR-109). version for optimistic locking (ADR-027).
CREATE TABLE customers (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    name               VARCHAR(150)  NOT NULL,
    customer_type      VARCHAR(30)   NOT NULL DEFAULT 'breeder',
    category_id        INT           NULL,
    farming_type       VARCHAR(100)  NULL,
    wilaya             VARCHAR(60)   NULL,
    commune            VARCHAR(80)   NULL,
    address            TEXT          NULL,
    phone              VARCHAR(40)   NULL,
    phone_key          VARCHAR(20)   NULL,
    email              VARCHAR(150)  NULL,
    tax_id             VARCHAR(40)   NULL,
    payment_terms_days INT           NOT NULL DEFAULT 0,
    veterinarian_id    BIGINT        NULL,
    credit_limit       DECIMAL(14,2) NULL DEFAULT 0,
    is_active          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_customers PRIMARY KEY (id),
    CONSTRAINT uq_customers_name_phone UNIQUE (name, phone_key),
    CONSTRAINT uq_customers_tax_id UNIQUE (tax_id),
    CONSTRAINT fk_customers_category FOREIGN KEY (category_id) REFERENCES customer_categories (id),
    CONSTRAINT fk_customers_veterinarian FOREIGN KEY (veterinarian_id) REFERENCES customers (id),
    CONSTRAINT ck_customers_name_not_blank CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_customers_type CHECK (customer_type IN ('breeder', 'veterinarian', 'other')),
    CONSTRAINT ck_customers_payment_terms CHECK (payment_terms_days >= 0),
    CONSTRAINT ck_customers_credit_limit CHECK (credit_limit IS NULL OR credit_limit >= 0),
    INDEX idx_customers_name (name),
    INDEX idx_customers_zone (wilaya, commune)
);
