-- receipt.cancel: cancel a whole goods receipt (ADR-103, security.md §4), for Administrator and Pharmacist.
INSERT INTO permissions (code, description) VALUES ('receipt.cancel', 'cancel a whole goods receipt');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'receipt.cancel'
WHERE r.name IN ('Administrator', 'Pharmacist');
