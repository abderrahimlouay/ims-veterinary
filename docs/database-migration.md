# PostgreSQL → MySQL Adaptation Analysis

> **Status:** Documentation / Architecture Phase — Implementation Not Started
> **Input:** `ims_veterinaire_schema_v2.sql` (PostgreSQL 14+, "the Source Schema")
> **Output of this analysis:** the *Schema Delta Register* (§8) that `database.md` applies.
> **Last updated:** 2026-10-18

This document does **not** contain a converted script. It analyses every PostgreSQL-specific construct, decides whether it converts directly, needs redesign, or moves into Spring Boot, and records the consequences. The existing relational design (44 tables, same entities, same relationships, same naming) is **preserved**; only constructs MySQL cannot express are adapted.

## 1. Method and classification

Every construct is placed in exactly one class:

| Class | Meaning |
|---|---|
| **A — Direct** | Valid in MySQL 8 without change. |
| **B — Convertible** | Same intent, different syntax or type; mechanical conversion. |
| **C — Redesign** | No MySQL equivalent; an alternative design is required. |
| **D — Application** | The logic must move from the database to Spring Boot. |

Enforcement vocabulary (used here and in `business-rules.md`):

- **Database-enforced rule** — guaranteed by the schema (key, `NOT NULL`, `UNIQUE`, `CHECK`, generated column, trigger). Holds even if the application has a bug.
- **Application/service-enforced rule** — guaranteed only by Spring Boot code inside a transaction (ADR-007). The database cannot detect a violation.

## 2. Inventory of the Source Schema

| Object | Count | Notes |
|---|---|---|
| Tables | 44 | roles, permissions, role_permissions, users, app_settings, audit_log, backup_log, manufacturers, therapeutic_categories, dosage_forms, active_ingredients, animal_species, products, product_barcodes, product_active_ingredients, product_withdrawal_periods, product_units, suppliers, supplier_price_history, customer_categories, customers, customer_product_prices, lots, stock_movements, inventories, inventory_lines, reorder_proposals, document_sequences, purchase_orders, purchase_order_lines, goods_receipts, goods_receipt_lines, supplier_returns, supplier_return_lines, cash_sessions, sales_documents, sales_document_lines, customer_returns, customer_return_lines, customer_payments, payment_allocations, supplier_invoices, supplier_payments, cash_movements |
| ENUM types | 12 | customer_type, lot_status, movement_type, po_status, sales_doc_type, sales_doc_status, payment_method, return_disposition, inventory_type, inventory_status, cash_session_status, cash_move_direction |
| Views | 8 | v_product_stock, v_reorder_needed, v_expiring_lots, v_sale_withdrawal_warnings, v_customer_balance, v_supplier_payables, v_stock_valuation, v_sales_margin |
| Functions | 4 | `next_doc_number` (PL/pgSQL), `trg_movements_immutable` (PL/pgSQL trigger fn), `fefo_lots` (SQL, returns TABLE), `recall_lot` (PL/pgSQL, returns TABLE) |
| Triggers | 1 | `stock_movements_no_update` (`BEFORE UPDATE OR DELETE`) |
| Indexes | 19 | of which 3 are partial (`idx_lots_fefo`, `idx_lots_expiry`, `uq_one_open_session`) and 2 are functional (`lower(...)`) |
| Generated columns | 5 | `inventory_lines.variance_qty`, `purchase_order_lines.line_ht`, `cash_sessions.variance_amount`, `sales_document_lines.line_ht`, `sales_document_lines.line_ttc` |
| `TIMESTAMPTZ` columns | 25 | + 1 in `recall_lot` return type |
| `JSONB` columns | 2 | `audit_log.old_values`, `audit_log.new_values` |
| `INET` columns | 1 | `audit_log.ip_address` |
| Serial keys | 37 | 29 `BIGSERIAL`, 7 `SERIAL`, 1 `SMALLSERIAL` |
| `CHECK` constraints | ≈ 45 | incl. 1 cross-column and 1 enum-cast check |
| `ON DELETE CASCADE` FKs | 15 | child rows only |
| Seed data | 4 roles, 7 species, 5 categories, 5 dosage forms, 2 settings | |

## 3. Feature-by-feature analysis

Legend for *Where*: **DB** = enforced in MySQL; **APP** = enforced in Spring Boot; **DB+APP** = both (DB as safety net).

| # | PostgreSQL feature | Current usage in Source Schema | MySQL equivalent | Direct? | Redesign? | Recommended solution | Where | Consequences for the application |
|---|---|---|---|---|---|---|---|---|
| 1 | `CREATE TYPE … AS ENUM` | 12 types used as column types and in `CHECK … ::text LIKE` | Inline `ENUM(...)` per column | No | Minor (B) | `VARCHAR(30)` + named `CHECK (col IN (…))`; Java `enum` with `@Enumerated(STRING)`. **ADR-009** | DB (value domain) + APP (transitions) | New value = migration + Java enum change. Values stay snake_case. |
| 2 | `SERIAL / BIGSERIAL / SMALLSERIAL` | 37 PKs | `INT/BIGINT/SMALLINT AUTO_INCREMENT` | Mechanical (B) | No | Signed integer types; **FK column type and signedness must equal the PK's**. | DB | JPA `GenerationType.IDENTITY` ⇒ Hibernate cannot batch-insert those entities; acceptable at this volume. Gaps in ids on rollback are normal and never shown as document numbers. |
| 3 | `TIMESTAMPTZ` | 25 columns, `DEFAULT now()` | `DATETIME(6)` storing UTC (`TIMESTAMP` rejected: 2038 limit, implicit zone conversion) | B | No | **ADR-010**: `DATETIME(6)`, UTC, `Instant`; default `CURRENT_TIMESTAMP(6)` plus application-set values from an injected `Clock`. | DB (type) + APP (UTC discipline) | JDBC URL/session time zone must be UTC; "today" for expiry uses the business time zone `Africa/Algiers` (ADR-033). |
| 4 | `JSONB` | `audit_log.old_values/new_values` (+ `jsonb_build_object` inside `recall_lot`) | `JSON` | B | No | `JSON` column; JPA mapping with `@JdbcTypeCode(SqlTypes.JSON)` or a `String`/converter. | DB | No GIN index; audit queries filter by `entity`, `entity_id`, `user_id`, date only (indexed). JSON key order is not preserved (same as JSONB). |
| 5 | `INET` | `audit_log.ip_address` | none | C (minor) | Minor | `VARCHAR(45)` (fits IPv6 textual form). Validation in app. | APP | IP validated in Java before write. |
| 6 | `NUMERIC(p,s)` | money, quantities, percentages | `DECIMAL(p,s)` | A/B | No | Same precision/scale, except prices per base unit at 4 decimals (ADR-049); Java `BigDecimal`. | DB | Never use `double/float`. |
| 7 | `BOOLEAN` | flags, `DEFAULT TRUE/FALSE`, used in `CHECK` | `BOOLEAN` = `TINYINT(1)` | A | No | Keep. | DB | JPA `boolean`. |
| 8 | `TEXT`, `VARCHAR(n)` | descriptions, notes | `TEXT`, `VARCHAR(n)` | A | No | Keep; charset `utf8mb4`. | DB | Row-size limit not a concern here. |
| 9 | `DEFAULT CURRENT_DATE` | `supplier_price_history.valid_from`, `purchase_orders.order_date` | `DEFAULT (CURRENT_DATE)` (expression default, 8.0.13+) | B | No | Prefer application-supplied value (business time zone); keep expression default as fallback. | APP | Value uses business date, not server date. |
| 10 | Function as column `DEFAULT` (`next_doc_number('PO')`) | `purchase_orders.number`, `goods_receipts.number`, `supplier_returns.number`, `customer_returns.number` | **Not possible** (defaults cannot call stored functions with side effects) | No | **Yes (C+D)** | Remove the `DEFAULT`; `NumberingService` assigns the number in the same transaction. **ADR-011**, **OD-017** | APP | Documents cannot be inserted by raw SQL without a number (good). Numbering concurrency tested. |
| 11 | `INSERT … ON CONFLICT (…) DO UPDATE … RETURNING` | inside `next_doc_number` | `INSERT … ON DUPLICATE KEY UPDATE`; no `RETURNING` | No | Yes (D) | Row lock + read + update in one transaction (`SELECT … FOR UPDATE`). | APP | Sequence row is a hot spot per `(doc_key, year)`; acceptable. |
| 12 | PL/pgSQL trigger function + `CREATE TRIGGER … BEFORE UPDATE OR DELETE` | `stock_movements` append-only guard | MySQL triggers: **one event per trigger**, body uses `SIGNAL SQLSTATE '45000'` | No | Minor (B) | Two triggers (`BEFORE UPDATE`, `BEFORE DELETE`). **ADR-017**. Also revoke `UPDATE/DELETE` from the app DB user and map entity `@Immutable`. | DB | `TRUNCATE` is not covered by triggers (guard by privileges). Cascaded FK actions do not fire triggers (no cascade targets this table). With binary logging, trigger creation needs `log_bin_trust_function_creators=1` or elevated migration rights. |
| 13 | `fefo_lots(product_id)` — SQL function `RETURNS TABLE` | FEFO ordering | MySQL stored functions return scalars only | No | **Yes (D)** | Repository query `ORDER BY expiry_date ASC, id ASC` filtered on sellable lots, with locking read inside the sale transaction. | APP | FEFO logic and the sellable predicate (status, expiry, quantity) are in Java + one query; one place only (BR-LOT-005). |
| 14 | `recall_lot(...)` — PL/pgSQL (update + audit insert + `RETURN QUERY`) | lot recall | stored procedure possible but not wanted | No | **Yes (D)** | `LotRecallService` (`@Transactional`): block lot, write audit, return recall report via query. | APP | Recall report must also cover anonymous sales and (per OD-002) delivery notes. |
| 15 | Partial indexes (`… WHERE …`) | `idx_lots_fefo`, `idx_lots_expiry`, `uq_one_open_session` | none | No | **Yes (C)** | **ADR-015**: ordinary composite indexes for the two lot indexes; generated column `open_cashier_id` + `UNIQUE` for one-open-session. | DB (+APP check) | Slightly larger indexes; planner may scan non-active lots; negligible volume. |
| 16 | Functional indexes `lower(col)` | `idx_products_name_trgm`, `idx_customers_name` | functional key parts (8.0.13+) — but unnecessary | B | No | Default collation `utf8mb4_0900_ai_ci` is already case/accent-insensitive; plain index on the column. **ADR-024, ADR-025** | DB | Search semantics differ from PostgreSQL (accent-insensitive). |
| 17 | `pg_trgm` GIN (mentioned in comment) | fuzzy name search idea | contains `LIKE` with `_ai_ci` collation | No | Decided | **ADR-048** | DB / APP | NFR-002 (< 1 s) must be verified with realistic data volume. |
| 18 | `GENERATED ALWAYS AS (…) STORED` | 5 columns | Same syntax, STORED/VIRTUAL | A/B | No | **ADR-016**: keep; read-only JPA mapping; parity test with Java formula. | DB | Hibernate must not insert/update them; entity reloaded/refreshed to see values. |
| 19 | `CHECK` constraints | ≈ 45 | Enforced from **8.0.16**; violation = error **3819** (SQLSTATE `HY000`) | A (mostly) | No | Keep; services pre-validate and translate error 3819 to a business error. MySQL forbids `CHECK` on columns used in FK referential actions (`CASCADE/SET NULL`) — verify no column is affected (none identified). | DB + APP | Without service pre-checks, users would see a generic integrity error. |
| 20 | `movement_type::text NOT LIKE 'adjustment_%'` | `chk_adjustment_reason` | `movement_type NOT LIKE 'adjustment\_%'` and `CHAR_LENGTH(TRIM(reason)) > 0` | B | No | Rewrite; escape `_` (it is a one-character wildcard in both databases — the Source expression matches more than intended). | DB | `reason` must be non-blank for all `adjustment_*` movements. |
| 21 | `SUM(x) FILTER (WHERE …)` | `v_product_stock` | `SUM(CASE WHEN … THEN x END)` | B | No | Rewrite. | DB (view) | — |
| 22 | `date_trunc('day', ts)::date`, `x::text`, `date - date`, `CURRENT_DATE + 60` | views | `DATE(ts)`, `CAST`, `DATEDIFF(a, b)`, `CURDATE() + INTERVAL 60 DAY` | B | No | Rewrite views (see §6). | DB (view) | Alert thresholds in `v_expiring_lots` are hard-coded 15/30/60 while the Spec says *configurable* (DR-019) — compute alert level in the service from `app_settings`. |
| 23 | `CREATE OR REPLACE FUNCTION … $$ … $$ LANGUAGE …` | 4 functions | different syntax (`DELIMITER`, `BEGIN…END`) | — | — | Not used except the trigger bodies. | — | Flyway executes trigger bodies natively; no `DELIMITER` handling in app code. |
| 24 | **Transactional DDL** (`BEGIN; … COMMIT;` around the whole script) | atomic install | **MySQL DDL implicitly commits** | No | Process change | **ADR-018**: small idempotent migrations; backup before production runs; never edit applied migrations. | Ops | A failed migration can leave a partial schema — recovery = restore backup or repair via Flyway. |
| 25 | Default isolation READ COMMITTED | implicit | InnoDB default **REPEATABLE READ** | No | Config | **ADR-013**: READ COMMITTED + explicit locking reads. | Config + APP | FEFO/credit checks use `FOR UPDATE`; reports are statement-consistent. |
| 26 | Row locking | none (logic lived in triggers originally) | `SELECT … FOR UPDATE [NOWAIT | SKIP LOCKED]` (8.0.1+) | A | No | **ADR-012**. | APP | Deterministic lock order (by `lots.id`). Set a short `innodb_lock_wait_timeout` `[REC]`. |
| 27 | `UNIQUE` with `NULL` | `users.email` | Multiple `NULL`s allowed in `UNIQUE` (same as PG) | A | No | Keep. | DB | — |
| 28 | Case sensitivity of `UNIQUE` / comparisons | `username`, `sku`, `lot_number` | Depends on collation; default is case-insensitive | Behaviour change | Decision | **ADR-024**; decided by ADR-041 (`username`), ADR-051 (`sku`), ADR-058 (`lot_number` insensitive, barcodes `utf8mb4_bin`) | DB | `Admin` = `admin`; `LOT-a1` = `LOT-A1`; barcodes match exactly. |
| 29 | Reserved words | `app_settings.key` | `KEY` is reserved | No | Rename | `setting_key`, `setting_value` (**ADR-025**) | DB | Entity field names follow. |
| 30 | `GROUP BY p.id` selecting other `p.*` columns | views | Allowed under `ONLY_FULL_GROUP_BY` via functional dependence on PK | A | No | Keep; verify `v_product_stock` (LEFT JOIN on the non-preserved side) at implementation. | DB | — |
| 31 | `FOREIGN KEY` semantics | 76 `REFERENCES`, 15 `ON DELETE CASCADE`, self-references | Same; `ON UPDATE/DELETE` default `RESTRICT/NO ACTION` | A | No | Keep; name constraints explicitly. | DB | `CASCADE` only on child detail rows (never on ledger/documents). |
| 32 | Strictness of data modes | PostgreSQL always strict | MySQL depends on `sql_mode` | Config | — | Require `STRICT_TRANS_TABLES`, `ONLY_FULL_GROUP_BY`, `NO_ENGINE_SUBSTITUTION`, `ERROR_FOR_DIVISION_BY_ZERO`. | Config | Silent truncation is impossible. |
| 33 | Table-name case | lower-case | Linux default is **case-sensitive** table names | A | No | Keep all identifiers lower-case; Spring's snake-case naming strategy. | DB/APP | — |
| 34 | `DESC` in index definition | `idx_audit_user_date`, `idx_sm_product_date`, `idx_sph`, `idx_sd_customer` | Supported (8.0) | A | No | Keep. | DB | — |
| 35 | `INSERT … VALUES` seed data | roles, species, categories, forms, settings | Same | A | No | Seed through Flyway data migrations (not in the first schema migration). | DB | Permission catalogue is **not** seeded in the Source — proposed in `security.md`. |

## 4. Enumeration conversion (12 types)

All become `VARCHAR(30)` + `CHECK` (ADR-009).

| Source enum | Values | Used by |
|---|---|---|
| customer_type | breeder, veterinarian, other | customers |
| lot_status | active, blocked, recalled, expired, depleted | lots |
| movement_type | purchase_receipt, sale, customer_return, supplier_return, adjustment_breakage, adjustment_expiry, adjustment_theft, adjustment_internal_use, adjustment_inventory, opening_stock (`unit_conversion` removed, ADR-061) | stock_movements |
| po_status | draft, validated, sent, partially_received, closed, cancelled | purchase_orders |
| sales_doc_type | quote, delivery_note, invoice, receipt, credit_note | sales_documents |
| sales_doc_status | draft, confirmed, cancelled | sales_documents |
| payment_method | cash, cheque, bank_transfer | customer_payments, supplier_payments, cash_movements |
| return_disposition | restock, discard | customer_return_lines |
| inventory_type | full, cycle | inventories |
| inventory_status | open, closed, cancelled | inventories |
| cash_session_status | open, closed | cash_sessions |
| cash_move_direction | in, out | cash_movements |

Open points on values: additional `movement_type` values for cancellations/discards (**OD-004**, **OD-005**); `unit_conversion` is removed because stock is kept in base units only (ADR-061, SD-118).

## 5. Type mapping reference

| PostgreSQL | MySQL | Java | Note |
|---|---|---|---|
| SMALLSERIAL | `SMALLINT AUTO_INCREMENT` | `Short` | `roles.id` and FK `users.role_id` must both be `SMALLINT` (signed) |
| SERIAL | `INT AUTO_INCREMENT` | `Integer` | reference tables |
| BIGSERIAL | `BIGINT AUTO_INCREMENT` | `Long` | business tables |
| NUMERIC(14,2) / (14,3) / (14,4) / (5,2) | `DECIMAL(14,2)` / … | `BigDecimal` | money / quantity / cost / percent |
| VARCHAR(n), TEXT | same | `String` | `utf8mb4` |
| BOOLEAN | `BOOLEAN` (`TINYINT(1)`) | `boolean` | |
| DATE | `DATE` | `LocalDate` | |
| TIMESTAMPTZ | `DATETIME(6)` (UTC) | `Instant` | ADR-010 |
| JSONB | `JSON` | `JsonNode`/`String` | |
| INET | `VARCHAR(45)` | `String` | |
| ENUM | `VARCHAR(30)` + `CHECK` | Java `enum` | ADR-009 |

## 6. Views, functions and triggers — conversion decisions

| Source object | Class | Decision |
|---|---|---|
| `v_product_stock` | D | Not created: sellable stock needs the business date in the business time zone (ADR-055) and the single Java predicate (BR-STOCK-011); stock levels are computed by the service (ADR-080). |
| `v_reorder_needed` | D | Not created: sellable stock depends on the business date and expiry policy, so the service computes stock levels and reorder candidates (ADR-076, ADR-080). |
| `v_expiring_lots` | D | Not created: the service lists expiring lots and computes the level from configured thresholds (ADR-056, DR-019). |
| `v_sale_withdrawal_warnings` | A | Direct. Applicability rules **OD-028**. |
| `v_customer_balance` | B + **defect** | Do not reuse as-is; definition **OD-003** (DR-006). |
| `v_supplier_payables` | A/B | Direct; ignores advances (OD-016). |
| `v_stock_valuation` | A | Direct; "at sale price" uses `products.sale_price` per base unit. |
| `v_sales_margin` | B + **defect** | `date_trunc(...)::date` → `DATE(...)`; ignores credit notes (DR-007, OD-003). |
| `next_doc_number` | D | `NumberingService` (ADR-011). |
| `trg_movements_immutable` + trigger | B | Two MySQL triggers (ADR-017). |
| `fefo_lots` | D | Repository query (BR-LOT-005). |
| `recall_lot` | D | `LotRecallService` (BR-LOT-008). |

Illustrative fragments (conceptual, not a migration):

```text
-- date arithmetic
PostgreSQL: l.expiry_date - CURRENT_DATE
MySQL     : DATEDIFF(l.expiry_date, CURDATE())

-- filtered aggregate
PostgreSQL: SUM(q) FILTER (WHERE l.status = 'active' AND l.expiry_date >= CURRENT_DATE)
MySQL     : SUM(CASE WHEN l.status = 'active' AND l.expiry_date >= CURDATE() THEN q END)

-- one open session per cashier (partial unique index)
PostgreSQL: CREATE UNIQUE INDEX … ON cash_sessions (cashier_id) WHERE status = 'open'
MySQL     : generated column open_cashier_id = IF(status = 'open', cashier_id, NULL)
            + UNIQUE index on open_cashier_id   (many NULLs allowed)
```

## 7. Database-enforced vs application-enforced rules (summary)

### 7.1 Database-enforced (structural integrity)

| Concern | Mechanism |
|---|---|
| Referential integrity | 76 foreign keys (explicitly named) |
| SKU, barcode, username, role name, permission code uniqueness | `UNIQUE` |
| Lot identity `(product_id, lot_number)` (ADR-070; the Source added `expiry_date`) | `UNIQUE` |
| Document number uniqueness (`purchase_orders.number`, `goods_receipts.number`, `supplier_returns.number`, `customer_returns.number`, `(doc_type, number)` on `sales_documents`, `(supplier_id, invoice_number)`) | `UNIQUE` |
| No negative lot stock | `CHECK (quantity_on_hand >= 0)` |
| Positive quantities, non-negative prices, discount 0–100, `max_stock ≥ min_stock` | `CHECK` |
| Adjustment movements need a reason | `CHECK` (rewritten, §3 row 20) |
| Credit sale needs a customer | `CHECK (is_credit_sale = FALSE OR customer_id IS NOT NULL)` |
| Stock ledger immutability | two triggers (+ privileges: application account `SELECT, INSERT` only, ADR-087) |
| Line amounts, inventory/cash variances | generated columns |
| One open cash session per cashier | generated column + `UNIQUE` |
| Value domains of enumerations | `CHECK … IN (…)` |

### 7.2 Application/service-enforced (business logic)

Ledger ↔ balance consistency (BR-STOCK-002/007) · status transitions of lots/orders/documents/inventories · refusal of sales on expired/blocked/recalled lots · FEFO · requirement of `lot_id` at confirmation · credit-limit check · document numbering (ADR-011) · PO/receipt/return quantity reconciliation · payment allocation limits · cash-session expected amount · price resolution · audit writes · permission checks · reorder proposal generation · recall report · return-quantity limits · product/customer/supplier active-state checks.

The authoritative per-rule list with enforcement layers is `business-rules.md`.

## 8. Schema Delta Register

Differences between the Source Schema and the intended MySQL design. `database.md` marks each affected object with its SD id.

### 8.1 Required for MySQL (no choice)

| ID | Delta | Basis |
|---|---|---|
| SD-001 | 12 enums → `VARCHAR(30)` + `CHECK` | ADR-009 |
| SD-002 | Serial keys → `AUTO_INCREMENT` with matching signed FK types | §3 #2 |
| SD-003 | 25 `TIMESTAMPTZ` → `DATETIME(6)` UTC | ADR-010 |
| SD-004 | `JSONB` → `JSON` (2 columns) | §3 #4 |
| SD-005 | `INET` → `VARCHAR(45)` | §3 #5 |
| SD-006 | `app_settings.key/value` → `setting_key/setting_value` | ADR-025 |
| SD-007 | Remove `DEFAULT next_doc_number(...)` on 4 tables; numbering in service | ADR-011 |
| SD-008 | `stock_movements` guard → two triggers | ADR-017 |
| SD-009 | 3 partial indexes replaced; `cash_sessions.open_cashier_id` added | ADR-015 |
| SD-010 | Drop `lower()` functional indexes; rename `idx_products_name_trgm` → `idx_products_commercial_name` | ADR-024/025 |
| SD-011 | 8 views rewritten in MySQL syntax (and 2 flagged defective) | ADR-014 |
| SD-012 | `fefo_lots`, `recall_lot`, `next_doc_number` removed from DB (services) | §3 #10–14 |
| SD-013 | `CHECK` expressions rewritten (`LIKE` escape, `CHAR_LENGTH`) | §3 #20 |
| SD-014 | `DEFAULT CURRENT_DATE` → expression default / application value | §3 #9 |

### 8.2 Proposed additions (each linked to a decision; not applied silently)

| ID | Delta | Linked decision | Status |
|---|---|---|---|
| SD-101 | `version` columns for optimistic locking | ADR-027 | Accepted 2026-10-20 |
| SD-102 | Append-only triggers on `audit_log` (and possibly payment/cash ledgers) | ADR-017, OD-003 | Accepted 2026-10-20 for `audit_log`; ledgers open (OD-003) |
| SD-103 | Composite uniqueness `lots (id, product_id)` and composite FKs so that `stock_movements`, `goods_receipt_lines`, `sales_document_lines` cannot reference a lot of another product | DR-008, ADR-062 | Accepted 2026-10-22 (`lots` in Phase 3; each FK with its table) |
| SD-104 | Prices per base unit `DECIMAL(14,4)` | ADR-049 | Accepted 2026-10-21 |
| SD-105 | `product_barcodes.unit_id` (composite FK to `product_units(id, product_id)`) and `code` in `utf8mb4_bin` | ADR-057, ADR-058 | Accepted 2026-10-22 |
| SD-106 | `product_target_species` (target species) | ADR-047 | Accepted 2026-10-21 |
| SD-107 | New `movement_type` values (cancellation/discard) | OD-004, OD-005 | Open |
| SD-108 | Payment capture model for counter/anonymous sales | OD-003 | Open |
| SD-109 | Link supplier return ↔ supplier credit note | OD-016 | Open |
| SD-110 | Company profile / document header data | OD-026 | Open |
| SD-111 | Spring Session JDBC tables (no refresh-token or login-attempt table) | ADR-036, ADR-038 | Accepted 2026-10-20 |
| SD-114 | `users.must_change_password` | ADR-039 | Accepted 2026-10-20 |
| SD-112 | Uniqueness of one *pending* reorder proposal per product (generated-column technique) | ADR-079 | Accepted 2026-10-22 |
| SD-113 | Scope of cycle counts: `inventories.scope_category_id` and `inventory_scope_products` | ADR-073 | Accepted 2026-10-22 |
| SD-115 | `products.is_divisible` | ADR-050 | Accepted 2026-10-21 |
| SD-116 | `lots.status` stores only `active`, `blocked`, `recalled` (expired/depleted computed) | ADR-059 | Accepted 2026-10-22 |
| SD-117 | `lots.supplier_id` and its FK added in Phase 5 with `suppliers` | ADR-066 | Accepted 2026-10-22 |
| SD-118 | `unit_conversion` removed from `movement_type` | ADR-061 | Accepted 2026-10-22 |
| SD-119 | Lot identity `UQ(product_id, lot_number)` instead of the Source `(product_id, lot_number, expiry_date)` | ADR-070, DR-036 | Accepted 2026-10-22 |
| SD-120 | `inventory_lines`: `product_id`, nullable `lot_id` with found-lot columns, `balance_at_count`, `watermark_movement_id`, `counted_at/by`, `adjustment_movement_id`; variance against the watermark | ADR-072, ADR-074 | Accepted 2026-10-22 |
| SD-121 | `reorder_proposals.supplier_id` added in Phase 5; `suppressed`, `updated_at`, `dismissed_at/by` | ADR-079 | Accepted 2026-10-22 |
| SD-122 | New table `integrity_check_runs` | ADR-086 | Accepted 2026-10-22 |
| SD-123 | Separate migration and application accounts; per-table grants applied after each migration | ADR-087 | Accepted 2026-10-22 |

## 9. Operational requirements for MySQL

- **Two accounts (ADR-087, from Phase 4):** Flyway connects with the migration account (`IMS_DB_MIGRATION_USERNAME`/`_PASSWORD`, DDL and `GRANT` rights, plus the trigger rights of ADR-017); the application connects with the application account (`IMS_DB_USERNAME`/`_PASSWORD`). After every migration a Flyway callback grants the application account `SELECT, INSERT, UPDATE, DELETE` on each application table and only `SELECT, INSERT` on `stock_movements` and `audit_log`; it never has DDL. Testcontainers and CI create both accounts the same way.
- **Lock wait (ADR-012):** application connections set `innodb_lock_wait_timeout = 5`.

| Setting | Value / recommendation | Reason |
|---|---|---|
| Version | ≥ 8.0.30 (CHECK enforcement since 8.0.16, `REVOKE IF EXISTS` for the grants of ADR-087), checked by the grants callback and the test container; target 8.4 LTS | ADR-003 |
| Engine / charset | InnoDB, `utf8mb4`, `utf8mb4_0900_ai_ci` | ADR-024 |
| `sql_mode` | `STRICT_TRANS_TABLES,ONLY_FULL_GROUP_BY,NO_ENGINE_SUBSTITUTION,ERROR_FOR_DIVISION_BY_ZERO` | No silent truncation |
| Server/JDBC time zone | UTC | ADR-010 |
| Isolation | `READ COMMITTED` (pool setting) | ADR-013 |
| `innodb_lock_wait_timeout` | short (e.g. 5–10 s) `[REC]` | POS must fail fast instead of freezing |
| `log_bin_trust_function_creators` | **`1` on every server** (tests, dev, prod); binary logging stays on | trigger creation without SUPER (ADR-043) |
| DB accounts | `migrator` (DDL), `app` (DML only; **no `UPDATE/DELETE/TRUNCATE` on `stock_movements`, `audit_log`**), `backup` (read + lock) | ADR-008, security |
| Backups | before every production migration | non-transactional DDL |

## 10. Validation checklist (to be executed in Phase 0 and each schema phase)

1. Every table in §2 exists in `database.md`; every deviation has an SD id.
2. No PostgreSQL syntax remains in any `docs/` file outside this document and the Source Schema excerpts.
3. Each view in §6 has a decided owner (read model) and a test comparing results with the equivalent repository query.
4. The generated-column parity test (ADR-016) exists before Phase 5 starts.
5. The concurrency test for numbering (ADR-011) and for last-unit sale (ADR-012) exist before Phase 7 closes.
6. MySQL error 3819 is mapped to a business error wherever a `CHECK` can be the last line of defence.
