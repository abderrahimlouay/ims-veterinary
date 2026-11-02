-- A product whose returned units must never go back into sellable stock (BR-PROD-009, ADR-129, SD-142): restocking it
-- into an active or blocked lot is refused; sealed units may still go back into a recalled lot, never sold again.
-- Existing products allow it, as before.
ALTER TABLE products ADD COLUMN return_restock_forbidden BOOLEAN NOT NULL DEFAULT FALSE AFTER is_archived;
