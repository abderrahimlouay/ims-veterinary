-- stock_movements is append-only (BR-STOCK-003, SD-008): one MySQL trigger per event (ADR-017). They stop every
-- account, including the migration account; the application account is already refused by its grants (ADR-087).
CREATE TRIGGER trg_stock_movements_no_update BEFORE UPDATE ON stock_movements FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stock_movements is append-only (BR-STOCK-003)';

CREATE TRIGGER trg_stock_movements_no_delete BEFORE DELETE ON stock_movements FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stock_movements is append-only (BR-STOCK-003)';
