# Database Architecture — Target MySQL Design

> **Status:** Documentation / Architecture Phase — Implementation Not Started
> **Derived from:** `ims_veterinaire_schema_v2.sql` (PostgreSQL) adapted per `database-migration.md`.
> **Last updated:** 2026-10-18

This document defines the **intended MySQL database** before any migration is written. It keeps the Source Schema's 44 tables, names and relationships. Deviations are tagged with Schema Delta ids (`SD-nnn`, register in `database-migration.md` §8). Items tagged **[OPEN]** depend on an Open Decision and **must not be implemented** until decided.

No final DDL is given here; column lists are the contract the first migrations must satisfy.

## 1. Conventions

| Topic | Convention |
|---|---|
| Engine / charset | InnoDB, `utf8mb4`, collation `utf8mb4_0900_ai_ci` (ADR-024) |
| Identifiers | lower-case snake_case; tables plural; FK columns `<entity>_id`; constraints named explicitly (`pk_`, `fk_<table>_<col>`, `uq_`, `ck_`, `idx_`) |
| Primary keys | `BIGINT AUTO_INCREMENT` for business tables, `INT` for reference tables, `SMALLINT` for `roles`; **signed**; FK type = PK type (SD-002) |
| Time | `DATETIME(6)` = UTC instant (SD-003); `DATE` for business dates |
| Money / quantity | `DECIMAL`; money `DECIMAL(14,2)`, quantities `DECIMAL(14,3)` (whole numbers for non-divisible products, ADR-050), **prices per base unit and unit cost `DECIMAL(14,4)`** (ADR-049), percent `DECIMAL(5,2)` |
| Enums | `VARCHAR(30)` + `CHECK` (SD-001); values in `database-migration.md` §4 |
| Booleans | `BOOLEAN` |
| Deletion | **No hard delete** of master or transactional data. `is_active` / `is_archived` / status columns instead. `ON DELETE CASCADE` only on pure child-detail rows |
| Concurrency | `version` column on human-edited aggregates (SD-101, ADR-027); row locks for stock/numbering (ADR-011/012) |
| Audit columns | `created_at` where the Source has it; `created_by/updated_by` where the Source has it (not added elsewhere) |

## 2. How key concepts are represented

### 2.1 Stock quantities
- **Unit of record: the product's base unit** (`products.base_unit`, e.g. `ml`, `tablet`, `syringe`). Every quantity stored in `lots`, `stock_movements`, `inventory_lines`, `*_lines.quantity_base` is in base units.
- **Authoritative history:** `stock_movements` (signed quantity: `+` in, `−` out). Immutable (ADR-008).
- **Maintained balance:** `lots.quantity_on_hand`, always equal to `SUM(stock_movements.quantity)` of that lot (ADR-028, BR-STOCK-007). `CHECK (quantity_on_hand >= 0)` makes negative stock impossible even if the application is wrong (NFR-001).
- **Product stock** = `SUM(lots.quantity_on_hand)`; **sellable stock** = same, restricted to lots with `status = 'active'` and not expired (BR-STOCK-011).
- There is **no stock-by-location** and no separate product-level stock column (A-001).

### 2.2 Lots
- A lot is identified by `(product_id, lot_number, expiry_date)` (unique). Receiving the same identity again increases the **existing** lot (find-or-create under lock) — BR-LOT-002.
- `unit_cost` is the cost per base unit of that lot (valuation, margin).
- `status` ∈ active, blocked, recalled, expired, depleted. Sellability is the conjunction *status = active ∧ expiry_date ≥ business date (boundary OD-009) ∧ quantity_on_hand > 0*. The `expired` and `depleted` values are maintained by the application (OD-027).
- `blocked_reason/blocked_by/blocked_at` document blocks and recalls.

### 2.3 Product units (packaging)
- `product_units(name, factor_to_base)`: "how many base units in one of this unit". Example: product *Solution X*, base unit `ml`; unit `bottle` factor 100; unit `dose` factor 5.
- The **base unit itself has no row**; a document line with `unit_id IS NULL` means "base unit" (factor 1).
- Document lines store both `quantity` (in the ordered/sold unit) and `quantity_base` (= `quantity × factor`), computed by the server (BR-UNIT-002).
- **Worked example:** receive 10 bottles → `goods_receipt_lines.quantity_base = 1000`, movement `+1000`. Sell 3 doses → `sales_document_lines.quantity = 3`, `unit_id = dose`, `quantity_base = 15`, movement `−15`, lot balance 985 ml. "Opening" a bottle is not an event: the stock is simply in millilitres (the `unit_conversion` movement type has no defined use — DR-018).
- Prices: `products.sale_price` and `purchase_price` are **per base unit**; `product_units.sale_price` optionally overrides per unit; precedence is **OD-006**.

### 2.4 Purchases
`purchase_orders` (header, status, totals) → `purchase_order_lines` (ordered quantity, `quantity_base`, `received_base`, price, discount, VAT, generated `line_ht`).
`goods_receipts` (+ `goods_receipt_lines`) record what physically arrived: each line points to a **lot** (so lot number and expiry are mandatory by construction), `quantity_base`, `unit_cost`, `is_conforming`. A receipt may reference a PO and a PO line (`po_line_id`), or neither (OD-015).
`supplier_invoices` record the supplier's bill (optionally tied to a receipt); `supplier_payments` settle them; `supplier_price_history` records negotiated prices/discounts over time.
`supplier_returns` (+ lines) send stock back from specific lots.

### 2.5 Sales
One table family handles every sales paper: `sales_documents.doc_type` ∈ quote, delivery_note, invoice, receipt, credit_note; `parent_id` chains quote → delivery note → invoice and invoice → credit note (OD-002 decides which types move stock).
Lines carry `product_id`, `lot_id` (required at confirmation for stock-moving documents), `unit_id`, `target_species_id` (drives withdrawal-period warnings), `quantity`, `quantity_base`, `unit_price` (per sold unit), `unit_cost` (snapshot of lot cost for margin), `discount_pct`, `vat_rate` (snapshot), generated `line_ht` / `line_ttc`.
Totals (`total_ht`, `total_discount`, `total_vat`, `total_ttc`) are computed by the server and stored (rounding: OD-007).
`customer_id IS NULL` allowed only for anonymous counter receipts (credit sales require a customer — DB `CHECK`).

### 2.6 Returns
- **Customer return:** `customer_returns` references the original sales document (`invoice_id`, which may also point to a receipt); each `customer_return_lines` row references the **original sale line** (`sale_line_id`), a `quantity_base`, and a `disposition` (restock | discard). The automatically issued credit note is a `sales_documents` row with `doc_type = 'credit_note'` linked by `credit_note_id`. Stock effects: restock ⇒ `customer_return` movement `+qty` on the original lot; discard ⇒ **OD-005**.
- **Supplier return:** `supplier_returns` + `supplier_return_lines(lot_id, quantity_base)`; stock effect: `supplier_return` movement `−qty`. Supplier credit linkage: **OD-016** (SD-109).

### 2.7 Payments and financial balances
- **Customer payments:** `customer_payments(customer, method, amount, reference, cash_session)`; `payment_allocations(payment_id, invoice_id, amount)` split a payment across invoices. Immediate payments at the counter and anonymous sales: **OD-003**.
- **Supplier payments:** `supplier_payments` optionally linked to one `supplier_invoices` row; `invoice_id IS NULL` + `is_advance` = advance (acompte); `supplier_invoices.is_credit_note = TRUE` is a supplier *avoir* stored as a positive amount and treated as negative in payables.
- **Cash:** `cash_sessions` (open/close, expected vs counted, generated `variance_amount`) and `cash_movements` (in/out, by method, optionally linked to a customer or supplier payment).
- **Balances are derived, never stored** (no balance column anywhere):

| Balance | Definition | Status |
|---|---|---|
| Invoice outstanding | `total_ttc − Σ payment_allocations.amount` for that invoice | `[REC]` |
| Customer balance (Source) | `Σ confirmed credit invoices − Σ confirmed credit notes − Σ customer_payments` | **Defective as written** (DR-006); final definition **OD-003** |
| Supplier payable (per invoice) | `±total_ttc − Σ supplier_payments(invoice)` | Source view `v_supplier_payables`; advances not netted (OD-016) |
| Expected cash in session | `opening_amount + Σ cash-method IN − Σ cash-method OUT` for the session | `[REC]` (BR-CASH-003) |

## 3. Table catalogue

Format: **Column | MySQL type | Null / default | Notes**. `PK`, `FK→`, `UQ`, `CK` are shorthand. Unless stated, `created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)`.

### 3.1 Security & administration (module 7)

**roles** — `id SMALLINT AI PK`, `name VARCHAR(40) NOT NULL UQ` (Administrator, Pharmacist, Seller, Accountant), `description TEXT`.

**permissions** — `id INT AI PK`, `code VARCHAR(80) NOT NULL UQ` (e.g. `sale.cancel`, `price.edit`), `description TEXT`. *Catalogue is not seeded in the Source; proposed in `security.md` §4.*

**role_permissions** — `role_id SMALLINT FK→roles ON DELETE CASCADE`, `permission_id INT FK→permissions ON DELETE CASCADE`, `PK(role_id, permission_id)`.

**users** — `id BIGINT AI PK`, `username VARCHAR(60) NOT NULL UQ`, `password_hash TEXT NOT NULL`, `full_name VARCHAR(150) NOT NULL`, `email VARCHAR(150) NULL UQ`, `role_id SMALLINT NOT NULL FK→roles`, `is_active BOOLEAN NOT NULL DEFAULT TRUE`, `last_login_at DATETIME(6) NULL`, `must_change_password BOOLEAN NOT NULL DEFAULT FALSE` (SD-114, ADR-039), `created_at`, `version` (SD-101). Hash = bcrypt cost 12 (ADR-039), never plaintext. `username` uses the default `utf8mb4_0900_ai_ci`: case- and accent-insensitive (ADR-041).

**app_settings** — `setting_key VARCHAR(80) PK` (SD-006), `setting_value TEXT NOT NULL`, `description TEXT`, `updated_at DATETIME(6) NOT NULL`. Seed: `expiry_alert_days = '60,30,15'`, `default_vat_rate = '9.00'` (Source values; VAT computation is OD-007), `login_max_failures = '5'`, `login_window_minutes = '15'`, `login_lockout_minutes = '15'` (ADR-038), `currency = 'DZD'`, `locale = 'fr-DZ'`, `business_time_zone = 'Africa/Algiers'` (ADR-033, ADR-044; only `locale` editable). Every key has a format (ADR-044); keys are created only by migrations.

**audit_log** — `id BIGINT AI PK`, `user_id BIGINT NULL FK→users`, `action VARCHAR(60) NOT NULL` (PRICE_CHANGE, INVOICE_CANCEL, STOCK_CORRECTION, LOT_RECALL…), `entity VARCHAR(60) NOT NULL`, `entity_id BIGINT NULL`, `old_values JSON NULL`, `new_values JSON NULL` (SD-004), `ip_address VARCHAR(45) NULL` (SD-005), `created_at`. Indexes: `(entity, entity_id)`, `(user_id, created_at DESC)`. Append-only (SD-102, BR-AUDIT-002): `BEFORE UPDATE` and `BEFORE DELETE` triggers raise `SQLSTATE '45000'` (ADR-017).

**SPRING_SESSION**, **SPRING_SESSION_ATTRIBUTES** — Spring Session JDBC tables for server-side sessions (SD-111, ADR-036), created by a Flyway migration from the MySQL schema shipped with Spring Session; not business data.

**backup_log** — `id BIGINT AI PK`, `file_path TEXT NOT NULL`, `size_bytes BIGINT NULL`, `status VARCHAR(20) NOT NULL DEFAULT 'success'`, `created_at`. Mechanism: OD-021.

### 3.2 Product catalogue (module 1)

**manufacturers** — `id INT AI PK`, `name VARCHAR(150) NOT NULL UQ`, `country VARCHAR(80) NULL`.
**therapeutic_categories** — `id INT AI PK`, `name VARCHAR(100) NOT NULL UQ`.
**dosage_forms** — `id INT AI PK`, `name VARCHAR(60) NOT NULL UQ`.
**active_ingredients** — `id INT AI PK`, `name VARCHAR(150) NOT NULL UQ` (DCI / INN).
**animal_species** — `id INT AI PK`, `name VARCHAR(60) NOT NULL UQ`.

**products**

| Column | Type | Null / default | Notes |
|---|---|---|---|
| id | BIGINT AI | PK | |
| sku | VARCHAR(40) | NOT NULL UQ | FR-PROD-003; case/accent-insensitive (ADR-051) |
| commercial_name | VARCHAR(200) | NOT NULL | indexed (SD-010) |
| dosage_form_id | INT | FK→dosage_forms | |
| category_id | INT | FK→therapeutic_categories | indexed |
| manufacturer_id | INT | FK→manufacturers | |
| base_unit | VARCHAR(20) | NOT NULL | smallest stock unit |
| is_divisible | BOOLEAN | NOT NULL | ADR-050, SD-115: whole quantities only when false |
| vat_rate | DECIMAL(5,2) | NOT NULL DEFAULT 9.00, CK ≥ 0 | default also in `app_settings` |
| purchase_price | DECIMAL(14,4) | NOT NULL DEFAULT 0, CK ≥ 0 | per base unit (ADR-049); last/average definition: OD-029 (Phase 5) |
| sale_price | DECIMAL(14,4) | NOT NULL DEFAULT 0, CK ≥ 0 | per base unit (ADR-049) |
| min_stock | DECIMAL(14,3) | NOT NULL DEFAULT 0, CK ≥ 0 | alert level |
| max_stock | DECIMAL(14,3) | NULL, CK ≥ min_stock | |
| reorder_point | DECIMAL(14,3) | NOT NULL DEFAULT 0, CK ≥ 0 | proposal trigger (OD-013) |
| is_archived | BOOLEAN | NOT NULL DEFAULT FALSE | FR-PROD-001 |
| created_at / updated_at | DATETIME(6) | NOT NULL | |
| version | BIGINT | NOT NULL DEFAULT 0 | SD-101 |

Indexes: `uq_products_sku`, `idx_products_commercial_name` (renamed, SD-010), `idx_products_category`; no FULLTEXT (ADR-048).

**product_barcodes** — `id BIGINT AI PK`, `product_id FK→products ON DELETE CASCADE`, `code VARCHAR(64) NOT NULL UQ`, `code_type VARCHAR(10) NOT NULL DEFAULT 'EAN13'` (EAN13, CODE128, QR). **[OPEN] `unit_id`** (SD-105, OD-019).

**product_active_ingredients** — `product_id FK→products CASCADE`, `ingredient_id FK→active_ingredients`, `strength VARCHAR(60) NULL`, `PK(product_id, ingredient_id)`, index on `ingredient_id` (substitution search).

**product_withdrawal_periods** — `product_id FK CASCADE`, `species_id FK→animal_species`, `meat_days SMALLINT NULL CK ≥0`, `milk_days`, `eggs_days`, `PK(product_id, species_id)`.

**product_units** — `id BIGINT AI PK`, `product_id FK CASCADE`, `name VARCHAR(40) NOT NULL`, `factor_to_base DECIMAL(14,4) NOT NULL CK > 0`, `sale_price DECIMAL(14,2) NULL CK ≥ 0`, `is_sellable BOOLEAN NOT NULL DEFAULT TRUE`, `UQ(product_id, name)`.

**product_target_species** — `product_id FK→products ON DELETE CASCADE`, `species_id FK→animal_species`, `PK(product_id, species_id)`, index on `species_id` (SD-106, ADR-047).

### 3.3 Suppliers & customers

**suppliers** — `id BIGINT AI PK`, `name VARCHAR(150) NOT NULL`, `supplier_kind VARCHAR(20) NOT NULL DEFAULT 'wholesaler' CK IN ('laboratory','wholesaler')`, `tax_id VARCHAR(40) NULL`, `phone`, `email`, `address TEXT`, `payment_terms_days SMALLINT NOT NULL DEFAULT 0 CK ≥ 0`, `is_active BOOLEAN NOT NULL DEFAULT TRUE`, `version` (SD-101).

**supplier_price_history** — `id BIGINT AI PK`, `supplier_id FK→suppliers`, `product_id FK→products`, `unit_price DECIMAL(14,4) NOT NULL CK ≥ 0` (per base unit, ADR-049), `discount_pct DECIMAL(5,2) NOT NULL DEFAULT 0 CK 0–100`, `valid_from DATE NOT NULL`. Index `(supplier_id, product_id, valid_from DESC)`.

**customer_categories** — `id INT AI PK`, `name VARCHAR(80) NOT NULL UQ`, `discount_pct DECIMAL(5,2) NOT NULL DEFAULT 0 CK 0–100`.

**customers** — `id BIGINT AI PK`, `name VARCHAR(150) NOT NULL`, `customer_type VARCHAR(30) NOT NULL DEFAULT 'breeder'` (breeder | veterinarian | other), `category_id INT NULL FK→customer_categories`, `farming_type VARCHAR(100) NULL`, `wilaya VARCHAR(60)`, `commune VARCHAR(80)`, `address TEXT`, `phone VARCHAR(40)`, `veterinarian_id BIGINT NULL FK→customers` (self; must reference a customer of type `veterinarian` — application rule BR-CUST-001), `credit_limit DECIMAL(14,2) NOT NULL DEFAULT 0 CK ≥ 0` (semantics of 0: OD-011), `is_active`, `created_at`, `version`. Indexes: `idx_customers_name`, `idx_customers_zone(wilaya, commune)`.

**customer_product_prices** — `customer_id FK CASCADE`, `product_id FK CASCADE`, `unit_price DECIMAL(14,4) NOT NULL CK ≥ 0` (per base unit, ADR-049; precedence OD-006), `PK(customer_id, product_id)`.

### 3.4 Lots & stock (modules 2 and 3)

**lots**

| Column | Type | Null / default | Notes |
|---|---|---|---|
| id | BIGINT AI | PK | |
| product_id | BIGINT | NOT NULL FK→products | |
| lot_number | VARCHAR(60) | NOT NULL | manufacturer lot (BR-LOT-001); collation OD-025 |
| expiry_date | DATE | NOT NULL | |
| supplier_id | BIGINT | NULL FK→suppliers | |
| unit_cost | DECIMAL(14,4) | NOT NULL DEFAULT 0, CK ≥ 0 | per base unit |
| quantity_on_hand | DECIMAL(14,3) | NOT NULL DEFAULT 0, **CK ≥ 0** | maintained balance (ADR-028) |
| status | VARCHAR(30) | NOT NULL DEFAULT 'active' | CK IN (active, blocked, recalled, expired, depleted) |
| blocked_reason | TEXT | NULL | |
| blocked_by | BIGINT | NULL FK→users | |
| blocked_at | DATETIME(6) | NULL | |
| received_at | DATETIME(6) | NOT NULL DEFAULT now | |

`UQ(product_id, lot_number, expiry_date)`. Indexes: `idx_lots_fefo(product_id, status, expiry_date)` and `idx_lots_expiry(expiry_date)` (SD-009). **[PROPOSED]** `UQ(id, product_id)` for composite FKs (SD-103).

**stock_movements** (append-only, ADR-008)

| Column | Type | Null / default | Notes |
|---|---|---|---|
| id | BIGINT AI | PK | |
| lot_id | BIGINT | NOT NULL FK→lots | |
| product_id | BIGINT | NOT NULL FK→products | must equal `lots.product_id` (SD-103) |
| movement_type | VARCHAR(30) | NOT NULL | CK IN (purchase_receipt, sale, customer_return, supplier_return, adjustment_breakage, adjustment_expiry, adjustment_theft, adjustment_internal_use, adjustment_inventory, opening_stock, unit_conversion) — additions: SD-107 |
| quantity | DECIMAL(14,3) | NOT NULL, **CK ≠ 0** | signed, base unit |
| reference_type | VARCHAR(40) | NULL | e.g. `sales_document`, `goods_receipt`, `inventory` |
| reference_id | BIGINT | NULL | |
| reason | TEXT | NULL | **CK**: required (non-blank) when `movement_type LIKE 'adjustment\_%'` |
| user_id | BIGINT | NOT NULL FK→users | operator |
| created_at | DATETIME(6) | NOT NULL | |

Indexes: `(product_id, created_at DESC)`, `(lot_id)`, `(reference_type, reference_id)`. Triggers: `BEFORE UPDATE`, `BEFORE DELETE` → `SIGNAL` (SD-008).

**inventories** (inventory count session) — `id BIGINT AI PK`, `inv_type VARCHAR(30) NOT NULL DEFAULT 'full'` (full | cycle), `status VARCHAR(30) NOT NULL DEFAULT 'open'` (open | closed | cancelled), `started_by FK→users`, `started_at`, `closed_at NULL`, `notes TEXT`, `version`. **[OPEN]** scope columns (SD-113, OD-012).

**inventory_lines** — `id BIGINT AI PK`, `inventory_id FK→inventories ON DELETE CASCADE`, `lot_id FK→lots`, `theoretical_qty DECIMAL(14,3) NOT NULL CK ≥ 0`, `counted_qty DECIMAL(14,3) NULL CK ≥ 0`, `variance_qty DECIMAL(14,3) GENERATED ALWAYS AS (counted_qty − theoretical_qty) STORED`, `regularized BOOLEAN NOT NULL DEFAULT FALSE`, `UQ(inventory_id, lot_id)`.

**reorder_proposals** — `id BIGINT AI PK`, `product_id FK`, `supplier_id NULL FK`, `current_qty DECIMAL(14,3) NOT NULL`, `suggested_qty DECIMAL(14,3) NOT NULL CK > 0`, `status VARCHAR(15) NOT NULL DEFAULT 'pending' CK IN (pending, converted, dismissed)`, `created_at`. **[OPEN]** one-pending-per-product uniqueness (SD-112, OD-013).

### 3.5 Purchasing (module 4)

**document_sequences** — `doc_key VARCHAR(30)`, `year SMALLINT`, `last_value INT NOT NULL DEFAULT 0`, `PK(doc_key, year)`. Row-locked by `NumberingService` (ADR-011). Key list: OD-017.

**purchase_orders** — `id BIGINT AI PK`, `number VARCHAR(30) NOT NULL UQ` (assigned by service — SD-007), `supplier_id FK`, `status VARCHAR(30) NOT NULL DEFAULT 'draft'` (draft | validated | sent | partially_received | closed | cancelled), `order_date DATE NOT NULL`, `expected_date DATE NULL`, `total_ht / total_vat / total_ttc DECIMAL(14,2) NOT NULL DEFAULT 0`, `created_by FK→users`, `created_at`, `version`.

**purchase_order_lines** — `id`, `purchase_order_id FK CASCADE`, `product_id FK`, `unit_id NULL FK→product_units`, `quantity DECIMAL(14,3) CK > 0` (ordered unit), `quantity_base DECIMAL(14,3) CK > 0`, `received_base DECIMAL(14,3) NOT NULL DEFAULT 0 CK ≥ 0`, `unit_price DECIMAL(14,2) CK ≥ 0` (per ordered unit), `discount_pct DECIMAL(5,2) CK 0–100`, `vat_rate DECIMAL(5,2) NOT NULL DEFAULT 0`, `line_ht DECIMAL(14,2) GENERATED ALWAYS AS (ROUND(quantity × unit_price × (1 − discount_pct/100), 2)) STORED`.

**goods_receipts** — `id`, `number VARCHAR(30) NOT NULL UQ` (service), `purchase_order_id NULL FK`, `supplier_id FK`, `supplier_doc_ref VARCHAR(60) NULL`, `received_at`, `received_by FK→users`, `notes TEXT`.

**goods_receipt_lines** — `id`, `receipt_id FK CASCADE`, `po_line_id NULL FK→purchase_order_lines`, `product_id FK`, `lot_id FK→lots` (lot number + expiry mandatory by construction), `quantity_base DECIMAL(14,3) CK > 0`, `unit_cost DECIMAL(14,4) CK ≥ 0`, `is_conforming BOOLEAN NOT NULL DEFAULT TRUE` (stock treatment: OD-014).

**supplier_returns** — `id`, `number VARCHAR(30) UQ` (service), `supplier_id FK`, `reason TEXT NOT NULL`, `created_by FK`, `created_at`. **supplier_return_lines** — `id`, `return_id FK CASCADE`, `lot_id FK`, `quantity_base CK > 0`.

### 3.6 Sales / POS (module 5)

**cash_sessions** — `id`, `cashier_id FK→users`, `status VARCHAR(30) NOT NULL DEFAULT 'open'` (open | closed), `opened_at`, `closed_at NULL`, `opening_amount DECIMAL(14,2) NOT NULL DEFAULT 0`, `expected_amount DECIMAL(14,2) NULL`, `counted_amount DECIMAL(14,2) NULL`, `variance_amount DECIMAL(14,2) GENERATED ALWAYS AS (counted_amount − expected_amount) STORED`, `open_cashier_id BIGINT GENERATED ALWAYS AS (IF(status='open', cashier_id, NULL)) … UQ` (SD-009).

**sales_documents** — `id`, `doc_type VARCHAR(30) NOT NULL`, `number VARCHAR(30) NOT NULL`, `status VARCHAR(30) NOT NULL DEFAULT 'draft'` (draft | confirmed | cancelled), `customer_id NULL FK`, `parent_id NULL FK→sales_documents`, `is_credit_sale BOOLEAN NOT NULL DEFAULT FALSE`, `due_date DATE NULL`, `cash_session_id NULL FK`, `issued_at`, `total_ht / total_discount / total_vat / total_ttc DECIMAL(14,2) NOT NULL DEFAULT 0`, `notes TEXT`, `created_by FK`, `cancelled_by NULL FK`, `cancelled_at NULL`, `version`. `UQ(doc_type, number)`; `CK (is_credit_sale = FALSE OR customer_id IS NOT NULL)`. Indexes `(customer_id, issued_at DESC)`, `(issued_at)`. **[OPEN]** payment-method/payment-status columns (SD-108, OD-003); number assignment moment (OD-017).

**sales_document_lines** — `id`, `document_id FK CASCADE`, `product_id FK`, `lot_id NULL FK→lots`, `unit_id NULL FK→product_units`, `target_species_id NULL FK→animal_species`, `quantity DECIMAL(14,3) CK > 0`, `quantity_base DECIMAL(14,3) CK > 0`, `unit_price DECIMAL(14,2) CK ≥ 0`, `unit_cost DECIMAL(14,4) NOT NULL DEFAULT 0`, `discount_pct DECIMAL(5,2) CK 0–100`, `vat_rate DECIMAL(5,2) NOT NULL DEFAULT 0`, `line_ht` and `line_ttc` generated (formulas in ADR-016). Indexes `(document_id)`, `(lot_id)` (recall), `(product_id)`.

**customer_returns** — `id`, `number VARCHAR(30) UQ` (service), `invoice_id NOT NULL FK→sales_documents`, `credit_note_id NULL FK→sales_documents`, `reason TEXT`, `created_by FK`, `created_at`.
**customer_return_lines** — `id`, `return_id FK CASCADE`, `sale_line_id FK→sales_document_lines`, `quantity_base CK > 0`, `disposition VARCHAR(30) NOT NULL` (restock | discard).

### 3.7 Finance (module 6)

**customer_payments** — `id`, `customer_id NOT NULL FK`, `method VARCHAR(30) NOT NULL` (cash | cheque | bank_transfer), `amount DECIMAL(14,2) CK > 0`, `reference VARCHAR(80) NULL`, `cash_session_id NULL FK`, `paid_at`, `created_by FK`. (`customer_id NOT NULL` conflicts with anonymous sales — OD-003.)
**payment_allocations** — `payment_id FK CASCADE`, `invoice_id FK→sales_documents`, `amount DECIMAL(14,2) CK > 0`, `PK(payment_id, invoice_id)`.
**supplier_invoices** — `id`, `supplier_id FK`, `invoice_number VARCHAR(60) NOT NULL`, `receipt_id NULL FK→goods_receipts`, `invoice_date DATE`, `due_date DATE`, `total_ttc DECIMAL(14,2) CK ≥ 0`, `is_credit_note BOOLEAN NOT NULL DEFAULT FALSE`, `UQ(supplier_id, invoice_number)`.
**supplier_payments** — `id`, `supplier_id FK`, `invoice_id NULL FK→supplier_invoices` (NULL = advance), `method`, `amount CK > 0`, `is_advance BOOLEAN NOT NULL DEFAULT FALSE`, `reference VARCHAR(80)`, `paid_at`, `created_by FK`.
**cash_movements** — `id`, `cash_session_id NULL FK`, `direction VARCHAR(30)` (in | out), `method`, `amount CK > 0`, `label VARCHAR(200)`, `customer_payment_id NULL FK`, `supplier_payment_id NULL FK`, `created_by FK`, `created_at`.

## 4. Indexing and search strategy

| Need | Structure |
|---|---|
| Product search (NFR-002 < 1 s) | contains `LIKE` on name, SKU and ingredient names with `_ai_ci` collation; exact SKU and name-prefix matches first (ADR-048); verified on the 50,000-product reference dataset |
| SKU / barcode scan | `uq_products_sku`, `uq_product_barcodes_code` (point lookup) |
| DCI search & substitution | `active_ingredients.name` UQ + `idx_pai_ingredient` |
| Category filter | `idx_products_category` |
| Species filter | `product_target_species` ∪ `product_withdrawal_periods` (ADR-047) |
| FEFO | `idx_lots_fefo (product_id, status, expiry_date)` + locking read |
| Expiry alerts | `idx_lots_expiry (expiry_date)` |
| Recall (who bought lot L) | `idx_sdl_lot (lot_id)` → `sales_documents` |
| Customer account history | `idx_sd_customer (customer_id, issued_at DESC)` |
| Movement history | `idx_sm_product_date`, `idx_sm_lot`, `idx_sm_reference` |
| Audit lookup | `idx_audit_entity`, `idx_audit_user_date` |

## 5. Views (target)

| View | Purpose | Status |
|---|---|---|
| `v_product_stock` | sellable vs total quantity per product, thresholds | Keep (rewrite `FILTER`) |
| `v_reorder_needed` | products at/below reorder point | Keep; trigger column per OD-013 |
| `v_expiring_lots` | expiry alert list | Keep for listing; thresholds from settings (DR-019) |
| `v_sale_withdrawal_warnings` | printable withdrawal warnings | Keep; applicability OD-028 |
| `v_customer_balance` | customer running balance | **Do not use as written** (DR-006); redefine after OD-003 |
| `v_supplier_payables` | outstanding per supplier invoice | Keep; advances OD-016 |
| `v_stock_valuation` | value at cost and at sale price | Keep |
| `v_sales_margin` | revenue / cost / margin by day, product, zone, customer | **Fix** (credit notes, delivery notes — DR-007, OD-002) |

Rule (ADR-014): views are read models for listings/reports; decisions inside transactions use locking queries.

## 6. Important queries (conceptual)

| Query | Tables | Key points |
|---|---|---|
| **FEFO lots for product P** | lots | `status='active' AND quantity_on_hand>0 AND expiry_date ≥ today` order by `expiry_date, id`; inside the sale transaction use `FOR UPDATE` in that order |
| **Sellable stock of P** | lots | sum over the same predicate (BR-STOCK-011) |
| **Expiry alerts** | lots, products | `quantity_on_hand>0 AND expiry_date ≤ today + max(threshold)`; level computed from `app_settings.expiry_alert_days` |
| **Recall report for lot L** | sales_document_lines, sales_documents, customers | confirmed stock-moving documents containing `lot_id = L`; **left join** customers (anonymous receipts must still appear); document types per OD-002 |
| **Ledger reconciliation** | lots, stock_movements | `lots.quantity_on_hand = SUM(stock_movements.quantity)` per lot; any difference is a Critical defect (BR-STOCK-007) |
| **Reorder candidates** | v_product_stock | `sellable_qty ≤ reorder_point` (OD-013) |
| **Withdrawal warnings for document D** | sales_document_lines, product_withdrawal_periods, animal_species | match species when specified |
| **Customer account statement** | sales_documents, payment_allocations, customer_payments | chronological, running balance (definition OD-003) |
| **Supplier payables** | supplier_invoices, supplier_payments | open amount per invoice, ordered by `due_date` |
| **Expected cash for session S** | cash_movements | cash method only (BR-CASH-003) |
| **Stock valuation** | lots, products | at cost: `Σ qty × lots.unit_cost`; at price: `Σ qty × products.sale_price` |
| **Margin** | sales_document_lines, sales_documents | `Σ line_ht − Σ quantity_base × unit_cost`, net of credit notes (OD-003) |
| **Customer sales by zone** | sales_documents, customers | group by `wilaya/commune`, period (FR-RPT-003) |

## 7. Transaction requirements

All rows below are **one database transaction** (`@Transactional`, READ COMMITTED, ADR-013). Lock order: `document_sequences` row → lots by ascending `id` → customer row (credit check) → other rows. A failure rolls back everything, including the numbering counter.

| Operation | Writes | Locks / checks |
|---|---|---|
| Confirm goods receipt | `goods_receipts`, `goods_receipt_lines`, `lots` (find-or-create), `stock_movements(+)`, `lots.quantity_on_hand`, `purchase_order_lines.received_base`, `purchase_orders.status`, `supplier_price_history`(opt.), `document_sequences`, `audit_log`(opt.) | lot row; PO row |
| Confirm sale (invoice/receipt) | `sales_documents`, `sales_document_lines`(lot, cost snapshot), `stock_movements(−)`, `lots`, payment rows (OD-003), `document_sequences`, `audit_log` | lots in `id` order; customer row if credit |
| Cancel confirmed document | status change, compensating movements, `audit_log` | lots; OD-004 |
| Customer return | `customer_returns/lines`, credit note document, movement(s) per disposition, `document_sequences`, `audit_log` | original lot; sale line |
| Supplier return | `supplier_returns/lines`, `stock_movements(−)`, `lots`, numbering | lot rows |
| Manual adjustment | `stock_movements(adjustment_*)`, `lots`, `audit_log` | lot row; reason mandatory |
| Inventory count regularisation | `inventory_lines.regularized`, `stock_movements(adjustment_inventory)`, `lots`, `inventories.status`, `audit_log` | affected lots |
| Lot block / recall | `lots.status/blocked_*`, `audit_log` | lot row |
| Record customer payment | `customer_payments`, `payment_allocations`, `cash_movements` | invoice rows (allocation ≤ outstanding) ; open cash session |
| Supplier invoice / payment | `supplier_invoices`, `supplier_payments`, `cash_movements` | invoice row |
| Open / close cash session | `cash_sessions` | unique-open guard (generated column) |
| Price change | `products`/`product_units`/`customer_product_prices`, `audit_log` | `@Version` |

## 8. Seed data

Roles (4), animal species (7), therapeutic categories (5), dosage forms (5), settings as in the Source Schema; the reference values are listed in ADR-052 (English; display labels OD-032), in their own data migration; manufacturers and active ingredients start empty. **Additional seed:** permission catalogue and role-permission mapping (`security.md` §4, ADR-037); login-throttle settings (ADR-038). The first administrator is created at startup from environment variables (ADR-040), never by a hard-coded password in a migration.

## 9. Data lifecycle

| Data | Rule |
|---|---|
| Master data (products, customers, suppliers, users) | Deactivate/archive; never delete once referenced |
| Stock movements, audit log | Append-only; retained indefinitely (retention policy OD-021) |
| Sales/purchase documents | Never physically deleted; cancelled documents keep their numbers |
| Drafts | May be deleted only while `draft` and with no number consumed (OD-017) |
| Backups | `backup_log` records each run (mechanism OD-021) |

## 10. Items awaiting decisions

SD-104…SD-113 and OD-002, 003, 004, 005, 006, 007, 011, 012, 013, 014, 015, 016, 017, 018, 019, 022, 025, 026, 027 each change this document. When a decision is made: update `decisions.md`, then this file, then `business-rules.md`, `api.md`, `testing.md`.
