-- Security and administration (database.md §3.1, ADR-029). Role codes and permission codes are seed data.
CREATE TABLE roles (
    id          SMALLINT    NOT NULL AUTO_INCREMENT,
    name        VARCHAR(40) NOT NULL,
    description TEXT        NULL,
    CONSTRAINT pk_roles PRIMARY KEY (id),
    CONSTRAINT uq_roles_name UNIQUE (name)
);

CREATE TABLE permissions (
    id          INT         NOT NULL AUTO_INCREMENT,
    code        VARCHAR(80) NOT NULL,
    description TEXT        NULL,
    CONSTRAINT pk_permissions PRIMARY KEY (id),
    CONSTRAINT uq_permissions_code UNIQUE (code)
);

CREATE TABLE role_permissions (
    role_id       SMALLINT NOT NULL,
    permission_id INT      NOT NULL,
    CONSTRAINT pk_role_permissions PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role_id FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_permission_id FOREIGN KEY (permission_id) REFERENCES permissions (id) ON DELETE CASCADE
);
