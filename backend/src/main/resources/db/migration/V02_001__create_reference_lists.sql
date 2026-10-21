-- Catalogue reference lists (database.md §3.2, FR-PROD-008). Names are unique case- and accent-insensitively
-- (utf8mb4_0900_ai_ci, ADR-051). Seeded rows carry a stable code that the UI translates and that cannot be
-- renamed or deleted (ADR-054); entries added by an administrator have no code.
CREATE TABLE manufacturers (
    id      INT          NOT NULL AUTO_INCREMENT,
    name    VARCHAR(150) NOT NULL,
    country VARCHAR(80)  NULL,
    CONSTRAINT pk_manufacturers PRIMARY KEY (id),
    CONSTRAINT uq_manufacturers_name UNIQUE (name),
    CONSTRAINT ck_manufacturers_name_not_blank CHECK (CHAR_LENGTH(TRIM(name)) > 0)
);

CREATE TABLE therapeutic_categories (
    id   INT          NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    code VARCHAR(40)  NULL,
    CONSTRAINT pk_therapeutic_categories PRIMARY KEY (id),
    CONSTRAINT uq_therapeutic_categories_name UNIQUE (name),
    CONSTRAINT uq_therapeutic_categories_code UNIQUE (code),
    CONSTRAINT ck_therapeutic_categories_name_not_blank CHECK (CHAR_LENGTH(TRIM(name)) > 0)
);

CREATE TABLE dosage_forms (
    id   INT         NOT NULL AUTO_INCREMENT,
    name VARCHAR(60) NOT NULL,
    code VARCHAR(40) NULL,
    CONSTRAINT pk_dosage_forms PRIMARY KEY (id),
    CONSTRAINT uq_dosage_forms_name UNIQUE (name),
    CONSTRAINT uq_dosage_forms_code UNIQUE (code),
    CONSTRAINT ck_dosage_forms_name_not_blank CHECK (CHAR_LENGTH(TRIM(name)) > 0)
);

CREATE TABLE active_ingredients (
    id   INT          NOT NULL AUTO_INCREMENT,
    name VARCHAR(150) NOT NULL,
    CONSTRAINT pk_active_ingredients PRIMARY KEY (id),
    CONSTRAINT uq_active_ingredients_name UNIQUE (name),
    CONSTRAINT ck_active_ingredients_name_not_blank CHECK (CHAR_LENGTH(TRIM(name)) > 0)
);

CREATE TABLE animal_species (
    id   INT         NOT NULL AUTO_INCREMENT,
    name VARCHAR(60) NOT NULL,
    code VARCHAR(40) NULL,
    CONSTRAINT pk_animal_species PRIMARY KEY (id),
    CONSTRAINT uq_animal_species_name UNIQUE (name),
    CONSTRAINT uq_animal_species_code UNIQUE (code),
    CONSTRAINT ck_animal_species_name_not_blank CHECK (CHAR_LENGTH(TRIM(name)) > 0)
);
