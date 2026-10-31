-- ADR-126: the Pharmacist sells at the counter, so the role may open and close its own cash session.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'cash.open' WHERE r.name = 'Pharmacist';
