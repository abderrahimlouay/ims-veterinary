-- The supplier a reorder proposal was converted for (ADR-102, SD-121): set exactly when the proposal is converted
-- into a draft purchase order (BR-PURCH-010).
ALTER TABLE reorder_proposals
    ADD COLUMN supplier_id BIGINT NULL AFTER dismissed_by,
    ADD CONSTRAINT fk_reorder_proposals_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    ADD CONSTRAINT ck_reorder_proposals_supplier CHECK ((status = 'converted') = (supplier_id IS NOT NULL));
