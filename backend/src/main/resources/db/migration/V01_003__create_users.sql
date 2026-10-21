-- Users (database.md §3.1). Hash only, bcrypt (ADR-039); must_change_password (SD-114);
-- version for optimistic locking (SD-101, ADR-027). username keeps the schema collation
-- utf8mb4_0900_ai_ci, so uniqueness is case- and accent-insensitive (ADR-041).
CREATE TABLE users (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    username             VARCHAR(60)  NOT NULL,
    password_hash        TEXT         NOT NULL,
    full_name            VARCHAR(150) NOT NULL,
    email                VARCHAR(150) NULL,
    role_id              SMALLINT     NOT NULL,
    is_active            BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at        DATETIME(6)  NULL,
    must_change_password BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           DATETIME(6)  NOT NULL,
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT fk_users_role_id FOREIGN KEY (role_id) REFERENCES roles (id),
    CONSTRAINT ck_users_username_not_blank CHECK (CHAR_LENGTH(TRIM(username)) > 0),
    CONSTRAINT ck_users_full_name_not_blank CHECK (CHAR_LENGTH(TRIM(full_name)) > 0)
);
