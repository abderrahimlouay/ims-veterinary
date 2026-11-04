-- The pharmacy's identity printed on documents (database.md, ADR-140, SD-147). Each change is a new version, never an
-- edit: a document prints the latest version in force at its issue time, so a reprint never changes.
CREATE TABLE company_profiles (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    legal_name      VARCHAR(150) NOT NULL,
    legal_form      VARCHAR(60)  NULL,
    address         TEXT         NULL,
    wilaya          VARCHAR(60)  NULL,
    phone           VARCHAR(40)  NULL,
    email           VARCHAR(150) NULL,
    nif             VARCHAR(40)  NULL,
    nis             VARCHAR(40)  NULL,
    rc              VARCHAR(40)  NULL,
    ai              VARCHAR(40)  NULL,
    approval_number VARCHAR(60)  NULL,
    bank_account    VARCHAR(60)  NULL,
    footer_text     VARCHAR(500) NULL,
    logo            MEDIUMBLOB   NULL,
    logo_type       VARCHAR(20)  NULL,
    effective_from  DATETIME(6)  NOT NULL,
    created_by      BIGINT       NOT NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_company_profiles PRIMARY KEY (id),
    CONSTRAINT fk_company_profiles_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_company_profiles_legal_name CHECK (TRIM(legal_name) <> ''),
    CONSTRAINT ck_company_profiles_logo CHECK ((logo IS NULL) = (logo_type IS NULL)),
    CONSTRAINT ck_company_profiles_logo_type CHECK (logo_type IS NULL OR logo_type IN ('image/png', 'image/jpeg')),
    CONSTRAINT ck_company_profiles_logo_size CHECK (logo IS NULL OR LENGTH(logo) <= 262144),
    INDEX idx_company_profiles_effective (effective_from)
);

CREATE TRIGGER trg_company_profiles_no_update BEFORE UPDATE ON company_profiles FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'company_profiles is append-only (ADR-140)';

CREATE TRIGGER trg_company_profiles_no_delete BEFORE DELETE ON company_profiles FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'company_profiles is append-only (ADR-140)';
