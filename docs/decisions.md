# Architecture Decisions — IMS Vétérinaire

> **Status:** Documentation / Architecture Phase — Implementation Not Started
> **Last updated:** 2026-10-20

This is the single register of architectural decisions (ADR) and unresolved questions (Open Decisions, `OD-nnn`).

## How to read this document

| Status | Meaning | What a coding LLM may do |
|---|---|---|
| **Accepted** | Given by the project owner's brief or required by the Spec. | Follow it. |
| **Proposed** | Recommendation by the documentation author with reasoning. Working assumption for planning. Must be confirmed (flipped to *Accepted*) **before the phase that depends on it starts**. | Follow it for design; flag it if the dependent phase begins while still *Proposed*. |
| **OPEN DECISION** | Cannot be decided from the Spec or Source Schema. The project owner must decide. | **Do not choose.** Stop, ask, then record the answer here. |

Rule: no decision in this file may be changed silently. A change is recorded as a new status line with date and reason.

---

# Part A — Architecture Decision Records

## ADR-001 — React + Vite for the frontend
- **Status:** Accepted (project brief)
- **Context:** A browser-based POS and back-office UI is required; a design system already exists (`Vet stock design system.html`, React + Tailwind tokens).
- **Decision:** Single-page application in React, built and served with Vite.
- **Alternatives:** Next.js (SSR not needed for an authenticated internal app); Angular/Vue (design system prototype is React).
- **Reason:** Fits the brief; the design-system prototype is already React; no SEO/SSR requirement.
- **Consequences:** Static build served by a web server; all data via REST; routing is client-side; see `frontend.md`.

## ADR-002 — Spring Boot (Java, Maven) for the backend
- **Status:** Accepted (project brief). Versions: see **OD-024**.
- **Context:** Transactional, rule-heavy domain (stock, finance) with strong need for transactions and testing.
- **Decision:** Java + Spring Boot, built with Maven.
- **Alternatives:** Node/NestJS, Quarkus, Gradle build.
- **Reason:** Mature transaction management, Spring Security, Spring Data JPA, Testcontainers support.
- **Consequences:** See `backend.md`.

## ADR-003 — MySQL as the database
- **Status:** Accepted (project brief); minimum raised to **8.0.30** by the user on 2026-10-23 (`REVOKE IF EXISTS` in the grants of ADR-087), checked at migration time and on the test container. Minimum features: **MySQL 8.0.30+** (enforced `CHECK` constraints since 8.0.16, functional indexes, `SKIP LOCKED`, `REVOKE IF EXISTS`). Target release line: **8.4 LTS** `[REC]` — MySQL 8.0 reached end of life in April 2026, so a new project should not start on it (verify at Phase 0, **OD-024**).
- **Context:** The Source Schema was designed for PostgreSQL 14+. The target is MySQL.
- **Decision:** MySQL (InnoDB, `utf8mb4`). The Source Schema is a *reference design*, not a script to convert mechanically.
- **Alternatives:** Keep PostgreSQL (rejected by brief); MariaDB (different `CHECK`/JSON/generated-column behaviour; not evaluated).
- **Reason:** Project owner's choice.
- **Consequences:** Every PostgreSQL-specific feature is analysed in `database-migration.md`. DDL is non-transactional in MySQL (migrations must be small and re-runnable). Default isolation level is REPEATABLE READ (see ADR-013).

## ADR-004 — REST API
- **Status:** Accepted (project brief)
- **Decision:** JSON over HTTPS, resource-oriented endpoints under `/api`. Conventions in `api.md`.
- **Alternatives:** GraphQL, gRPC.
- **Consequences:** Contract documented in `api.md`; OpenAPI can be generated from code later and must be diffed against `api.md`.

## ADR-005 — Spring Data JPA / Hibernate
- **Status:** Accepted (project brief)
- **Decision:** JPA entities map tables; Spring Data repositories for persistence; native/JPQL queries for reports.
- **Alternatives:** jOOQ, MyBatis, JdbcTemplate.
- **Consequences:** JPA entities are **never** exposed through the API (ADR-019). Generated columns and read-only views need explicit mapping (ADR-014, ADR-016). Hibernate `ddl-auto` must be `validate` or `none` (ADR-018).

## ADR-006 — Modular backend architecture (modular monolith)
- **Status:** Accepted (principle, project brief). The module list below is **superseded by ADR-034** (2026-10-20). ArchUnit accepted (user confirmation, 2026-10-20).
- **Context:** The brief asks for a layered/modular backend and permits deviation from the sample package list when justified.
- **Decision:** One deployable Spring Boot application, organised **package-by-module**; each module has its own `web`, `service`, `domain`, `repository`, `dto` sub-packages. Modules:
  `common`, `auth`, `user`, `settings`, `audit`, `numbering`, `product`, `inventory`, `supplier`, `purchasing`, `customer`, `sales`, `returns`, `payment`, `finance`, `reporting`, `printing`, `backup`.
- **Deviations from the brief's sample list and why:**
  1. **`lot` is merged into `inventory`.** `lots.quantity_on_hand` and `stock_movements` form one consistency boundary (BR-STOCK-002, BR-STOCK-007): they must always change together, in one transaction, through one gateway (`StockLedgerService`). Splitting them across modules would force cross-module access to the most critical invariant. Lot lifecycle (block/recall) and FEFO live in sub-package `inventory.lot`.
  2. **Added `numbering`** (document sequences) — shared by purchasing, sales and returns; avoids duplicating the concurrency-sensitive code (ADR-011).
  3. **Added `settings`, `printing`, `backup`, `common`** — cross-cutting concerns found in the Spec (alert thresholds, PDF/ticket output, backups).
  4. **`payment` vs `finance` split kept:** `payment` records money movements (customer/supplier payments, allocations); `finance` owns cash sessions, supplier invoices, balances/statements and the credit-limit policy.
- **Dependency rules:** see `architecture.md` §3. Cross-module calls go through the target module's public service interface only; sales defines a `CreditLimitPort` implemented by `finance` (dependency inversion) to avoid a sales↔finance cycle.
- **Alternatives:** Separate microservices (rejected: no scaling need, distributed transactions would endanger stock integrity); single flat layered package set (rejected: rule ownership unclear).
- **Consequences:** Module boundaries should be enforced by an automated architecture test (ArchUnit — dependency to be justified in Phase 0).

## ADR-007 — Business rules live in application services
- **Status:** Accepted (project brief)
- **Context:** The Source Schema deliberately keeps almost everything out of database triggers ("lean: minimal triggers").
- **Decision:** All business rules are enforced in Spring service/domain code inside a transaction. The database enforces only **structural integrity** (keys, `NOT NULL`, `CHECK`, uniqueness, append-only guard). The frontend only assists (validation messages, defaults).
- **Alternatives:** Rules in triggers/stored procedures (PostgreSQL original used PL/pgSQL functions); rules in React.
- **Reason:** Testability, portability, one place per rule, MySQL trigger/procedure limitations.
- **Consequences:** Every rule in `business-rules.md` has an *Enforcement layer*. Where the database is the last line of defence (e.g. `quantity_on_hand >= 0`), the service must still pre-check to return a meaningful error.

## ADR-008 — Stock movement history is immutable
- **Status:** Accepted (Spec §2.3 "historique inaltérable"; Source Schema trigger `stock_movements_no_update`)
- **Decision:** `stock_movements` is append-only. No `UPDATE`, no `DELETE`. Mistakes are corrected by compensating movements. Enforced by (1) MySQL triggers (two, ADR-017), (2) JPA `@Immutable` entity with no update/delete repository methods, (3) the application DB account has no `UPDATE`/`DELETE` privilege on this table `[REC]`.
- **Alternatives:** Soft-delete flags; event sourcing of the whole domain.
- **Consequences:** Cancellations/returns/corrections always create new rows. The set of movement types must be sufficient to express every correction (**OD-004**, **OD-005**).

## ADR-009 — Enumerations stored as `VARCHAR` + `CHECK`
- **Status:** ~~Proposed~~ → Accepted (user confirmation, 2026-10-20, Phase 0 gate)
- **Context:** PostgreSQL has 12 `CREATE TYPE … AS ENUM`. MySQL has only inline per-column `ENUM`.
- **Decision:** Store each enumeration as `VARCHAR(30)` with a named `CHECK (col IN (...))`, mapped to a Java `enum` with `@Enumerated(EnumType.STRING)`. Values keep the Source Schema's lower-snake-case spelling.
- **Alternatives:** Native MySQL `ENUM` (compact, but ordering/ALTER semantics, Hibernate schema-validation friction, harder reuse across tables); lookup tables with FKs (heavier, needed only if values become user-editable).
- **Reason:** Simple, portable, `LIKE`/`IN` checks (e.g. `adjustment_%`) work on plain strings, adding a value is a single `ALTER … CHECK`.
- **Consequences:** Adding an enum value = migration (alter CHECK) + Java change. Documented in `database-migration.md` §4.

## ADR-010 — Timestamps stored as UTC `DATETIME(6)`
- **Status:** ~~Proposed~~ → Accepted (user confirmation, 2026-10-20, Phase 0 gate). Business time zone: `Africa/Algiers` (ADR-033).
- **Context:** PostgreSQL `TIMESTAMPTZ` (25 columns) stores an instant. MySQL `TIMESTAMP` is limited to 2038 and converts via session time zone; `DATETIME` is zone-less.
- **Decision:** `DATETIME(6)` columns holding **UTC**; JVM and JDBC session set to UTC (`hibernate.jdbc.time_zone=UTC`); Java type `java.time.Instant`. Business-calendar logic (document year, "today" for expiry) uses an explicit business time zone setting and an injected `java.time.Clock`.
- **Alternatives:** `TIMESTAMP` (2038, implicit conversions); store local time (ambiguous at DST changes).
- **Consequences:** The frontend converts to local time for display. "Today" for expiry/numbering is computed in the **business time zone** (setting, **OD-008**), not the server's.

## ADR-011 — Document numbering via `document_sequences` row lock
- **Status:** Proposed (mechanism). Assignment time, format and gapless policy are **OD-017**.
- **Context:** Source Schema uses a PL/pgSQL function `next_doc_number()` as a **column `DEFAULT`**. MySQL cannot use a stored function with side effects as a default, and the pattern `INSERT … ON CONFLICT … RETURNING` does not exist.
- **Decision:** A `NumberingService` increments the `(doc_key, year)` row inside the **same transaction** that persists the document, using `SELECT … FOR UPDATE` (or `INSERT … ON DUPLICATE KEY UPDATE last_value = LAST_INSERT_ID(last_value + 1)`), and formats `KEY-YYYY-000001`. A rollback also rolls the counter back, so committed documents have no gaps.
- **Alternatives:** DB sequence emulation with AUTO_INCREMENT table (gaps on rollback), UUIDs (not human numbering), application-wide in-memory counter (breaks with restarts/concurrency).
- **Consequences:** Numbering is serialised per `(key, year)` — acceptable for a single-pharmacy load. The Source Schema's key list is inconsistent (comment lists `PO, INV, RCT, QUO, DN, CN`, code uses `PO, GR, SR, CR`): see **OD-017**.

## ADR-012 — Stock updates use pessimistic row locks and one transaction
- **Status:** ~~Proposed~~ → Accepted (user decision, 2026-10-22, Phase 4 preflight; #15 A), amended
- **Decision:** Every stock-changing use case runs in one `@Transactional` boundary (READ COMMITTED) and: (1) locks the affected `lots` rows with `SELECT … FOR UPDATE` **ordered by `lots.id`** (avoids deadlocks); (2) validates status/expiry/quantity; (3) inserts `stock_movements` rows; (4) applies the same signed delta to `lots.quantity_on_hand`; (5) persists the business document and the audit entry. The stored status is **not** touched by stock changes: depleted and expired are computed (ADR-059; the former step "update `lots.status` depleted/active" is removed). `CHECK (quantity_on_hand >= 0)` is the final safety net. Application connections set `innodb_lock_wait_timeout = 5` seconds (connection setting `ims.db.lock-wait-timeout-seconds`, applied when the pool opens a connection); a lock timeout (MySQL 1205) is answered `CONCURRENT_MODIFICATION` and never retried silently (BR-STOCK-012).
- **Alternatives:** Optimistic locking with retry (more retries at a busy counter, harder to reason about); atomic `UPDATE … WHERE quantity_on_hand + :delta >= 0` without prior read (OK for single-lot ops, awkward for multi-lot FEFO allocation).
- **Consequences:** Two sellers selling the last units of the same lot are serialised; one gets `STOCK_INSUFFICIENT`. Tested with concurrency tests (`testing.md`).

## ADR-013 — Transaction isolation: READ COMMITTED + explicit locks
- **Status:** ~~Proposed~~ → Accepted (user confirmation, 2026-10-20, Phase 0 gate)
- **Context:** PostgreSQL defaults to READ COMMITTED; MySQL/InnoDB defaults to **REPEATABLE READ**, where plain `SELECT`s read a snapshot and range scans take gap locks. A FEFO query could read a stale snapshot, and gap locks can cause unexpected deadlocks.
- **Decision:** Configure the connection pool to `READ COMMITTED`; use explicit locking reads (`FOR UPDATE`) for anything that decides on stock or balances.
- **Alternatives:** Keep REPEATABLE READ and rely only on locking reads.
- **Consequences:** Behaviour of reports is "statement-level consistent", not transaction-level. Reports needing a point-in-time snapshot open a read-only REPEATABLE READ transaction explicitly.

## ADR-014 — Views are read models; business decisions never rely on views
- **Status:** Proposed
- **Context:** 8 PostgreSQL views (`v_product_stock`, `v_reorder_needed`, `v_expiring_lots`, `v_sale_withdrawal_warnings`, `v_customer_balance`, `v_supplier_payables`, `v_stock_valuation`, `v_sales_margin`) use `FILTER`, `date_trunc`, `::date`, date arithmetic.
- **Decision:** Re-create them in MySQL syntax (conversion table in `database-migration.md`) **only** where they serve reporting/listing (read-only `@Immutable` entities or projections). Service-level decisions (e.g. credit-limit check, sellable quantity at confirmation) use explicit locking queries, not views.
- **Alternatives:** Drop views and use only repository queries; materialised views (not available in MySQL).
- **Consequences:** Some view definitions have known semantic defects (balance, margin) — see `documentation-review.md` DR-006, DR-007; they must be fixed before being relied upon (**OD-003**).

## ADR-015 — Replacement of PostgreSQL partial indexes
- **Status:** Proposed
- **Context:** 3 partial indexes: `idx_lots_fefo`, `idx_lots_expiry`, `uq_one_open_session`. MySQL has none.
- **Decision:**
  - `idx_lots_fefo` → ordinary composite index `(product_id, status, expiry_date)`.
  - `idx_lots_expiry` → ordinary index `(expiry_date)` (optionally `(quantity_on_hand, expiry_date)`).
  - `uq_one_open_session` (unique per cashier **only when open**) → a stored/virtual generated column `open_cashier_id = IF(status='open', cashier_id, NULL)` with a `UNIQUE` index (MySQL allows many `NULL`s in a unique index); the rule is **also** checked in `CashSessionService` for a friendly error.
- **Alternatives:** Application-only enforcement (race window); trigger.
- **Consequences:** Extra generated column on `cash_sessions` mapped read-only in JPA.

## ADR-016 — Generated columns retained for line amounts and variances
- **Status:** Proposed
- **Context:** 5 generated columns: `inventory_lines.variance_qty`, `purchase_order_lines.line_ht`, `cash_sessions.variance_amount`, `sales_document_lines.line_ht`, `sales_document_lines.line_ttc`. MySQL supports `GENERATED ALWAYS AS (…) STORED`.
- **Decision:** Keep them (direct conversion). Map as read-only in JPA (`insertable=false, updatable=false`) and refresh after insert/update. The service computes document totals with `BigDecimal` using **the identical formula and rounding mode** (HALF_UP, scale 2), verified by a parity test.
- **Alternatives:** Plain columns computed only by the service (risk of divergence from DB); compute on read.
- **Consequences:** Rounding policy is **OD-007**. MySQL evaluates `discount_pct/100` as `DECIMAL` with extra scale (`div_precision_increment`, default 4) — safe for the stated precision, tested.

## ADR-017 — Trigger policy: append-only guards only
- **Status:** ~~Proposed~~ → Accepted (user decision, 2026-10-20, Phase 1 preflight), including the `audit_log` guard (SD-102); payment/cash ledgers still follow OD-003
- **Context:** The Source Schema keeps exactly one trigger (`BEFORE UPDATE OR DELETE` on `stock_movements`). MySQL does not allow one trigger for two events.
- **Decision:** Create **two** triggers (`BEFORE UPDATE`, `BEFORE DELETE`) on `stock_movements` raising `SQLSTATE '45000'`. `[REC]` Apply the same guard to `audit_log` (Spec §2.7 "traçabilité complète") and to `customer_payments` / `cash_movements` if **OD-003** confirms history must be immutable. No other triggers.
- **Alternatives:** Revoke `UPDATE/DELETE` only (does not protect against privileged accounts).
- **Consequences:** If binary logging is enabled, creating triggers needs `log_bin_trust_function_creators=1` or SUPER-equivalent rights for the migration account (ops note in `database-migration.md`).

## ADR-018 — Schema migrations with Flyway; Hibernate validates only
- **Status:** ~~Proposed~~ → Accepted (user confirmation, 2026-10-20, Phase 0 gate). File naming amended 2026-10-20 (user decision, resolving a conflict with `development-workflow.md` §6): `V<phase>_<seq>__<description>.sql`, e.g. `V00_001__baseline.sql`.
- **Decision:** Versioned SQL migrations (`V<phase>_<seq>__…sql`) applied by Flyway at startup in dev/test and by a controlled step in production. `spring.jpa.hibernate.ddl-auto=validate`.
- **Alternatives:** Liquibase; Hibernate `update` (rejected: uncontrolled, destroys traceability).
- **Consequences:** Because **MySQL DDL is non-transactional** (implicit commit), each migration must be small, single-purpose and re-runnable; take a backup before production migrations. Applied migrations are never edited.

## ADR-019 — DTOs everywhere; entities never leave the service layer
- **Status:** ~~Proposed~~ → Accepted including MapStruct (user confirmation, 2026-10-20, Phase 0 gate). The dependency is added with the first mapper (Phase 1), not in Phase 0. *Implementation note 2026-10-21: Phase 1 uses the hand-written fallback — entities expose fluent accessors (`username()`), which MapStruct does not detect as properties without a custom naming strategy; the mappers are small static methods next to the views. Revisit when a module has many DTOs.*
- **Decision:** Controllers accept/return DTOs (Java `record`s). Mapping via MapStruct `[REC]` (new dependency — must be justified in Phase 0; hand-written mappers are the fallback).
- **Consequences:** Separate request and response DTOs per use case; no bidirectional entity serialisation.

## ADR-020 — Error responses use RFC 9457 Problem Details + business codes
- **Status:** ~~Proposed~~ → Accepted (user decision, 2026-10-20, Phase 1 preflight); implemented in Phase 0
- **Decision:** All errors return `application/problem+json` with `type`, `title`, `status`, `detail`, `instance` plus extensions `code` (stable business code, catalogue in `api.md` §3), `traceId`, and `errors[]` for field validation.
- **Alternatives:** Ad-hoc JSON error body.
- **Consequences:** The frontend switches on `code`, not on message text.

## ADR-021 — Decimal values travel as JSON strings
- **Status:** ~~Proposed~~ → Accepted (user decision, 2026-10-20, Phase 1 preflight); implemented in Phase 0
- **Decision:** Money, quantities, percentages are serialised as decimal **strings** (`"1234.50"`), parsed to `BigDecimal` in Java; the frontend uses them for display and never performs financial arithmetic that decides outcomes.
- **Alternatives:** JSON numbers (binary floating-point loss in JavaScript).
- **Consequences:** Client-side previews of totals are labelled "estimate"; the server is authoritative (BR-SALE-008).

## ADR-022 — Terminology: "inventory" vs "inventory count"
- **Status:** Proposed (resolves a naming ambiguity)
- **Context:** The brief uses `inventory/` as a package/endpoint name and "Physical Inventory" as a workflow; the Source Schema uses `inventories`/`inventory_lines` for the *counting session*.
- **Decision:** In prose: **Stock** = quantities/ledger; **Inventory Count** = the physical counting session (tables keep Source names `inventories`, `inventory_lines`). Package `inventory` = stock domain (lots, movements, adjustments, counts, reorder). API: `/api/inventory/...` for stock; counting sessions at `/api/inventory/counts`.
- **Consequences:** Glossary and all documents use these terms.

## ADR-023 — Frontend stack choices
- **Status:** ~~Proposed~~ → Accepted (user confirmation, 2026-10-20, Phase 0 gate). i18n library: i18next + react-i18next (ADR-033).
- **Decision:** TypeScript; React Router; TanStack Query (server state); React Hook Form + Zod (forms/validation mirrors); Tailwind CSS configured with the design-system tokens; no global client store beyond auth/UI preferences; Vitest + React Testing Library + MSW; Playwright for end-to-end smoke tests.
- **Alternatives:** Redux Toolkit, plain `fetch` state, CSS-in-JS.
- **Consequences:** Each library is a dependency to be justified in Phase 0 (CLAUDE.md rule). Detail in `frontend.md`.

## ADR-024 — Character set and collation
- **Status:** ~~Proposed~~ → Accepted (user confirmation, 2026-10-20, Phase 0 gate); case-sensitivity of codes is still **OD-025**
- **Decision:** `utf8mb4` with `utf8mb4_0900_ai_ci` (accent- and case-insensitive) as default — convenient for French/Arabic names and instant user-friendly search. **Consequence to document:** `UNIQUE` columns (username, sku, barcode, lot_number) become case/accent-insensitive, unlike PostgreSQL.
- **Alternatives:** `utf8mb4_0900_as_cs` / `utf8mb4_bin` for code columns.
- **Consequences:** `lower(col)` functional indexes from the Source Schema (`idx_products_name_trgm`, `idx_customers_name`) become unnecessary.

## ADR-025 — Reserved-word and naming adjustments
- **Status:** ~~Proposed~~ → Accepted (user confirmation, 2026-10-20, Phase 0 gate)
- **Decision:** `app_settings.key` → `setting_key`, `app_settings.value` → `setting_value` (`KEY` is a reserved word in MySQL). Rename `idx_products_name_trgm` (it is not a trigram index) to `idx_products_commercial_name`. All other identifiers are kept.
- **Consequences:** Entity/column names documented in `database.md`.

## ADR-026 — API base path and versioning
- **Status:** ~~Proposed~~ → Accepted (user confirmation, 2026-10-20, Phase 0 gate)
- **Decision:** Base path `/api` exactly as in the brief; no URL version segment while the only consumer is the bundled frontend. Introduce `/api/v2/...` only for a breaking change with an external consumer.
- **Consequences:** Backward-incompatible changes require coordinated frontend release.

## ADR-027 — Optimistic locking on mutable aggregates
- **Status:** ~~Proposed~~ → Accepted (user decision, 2026-10-20, Phase 1 preflight). The version travels as a `version` field in the request body (not `If-Match`).
- **Context:** Source Schema has no version columns; two users can overwrite each other's edits.
- **Decision:** Add `version BIGINT NOT NULL DEFAULT 0` (JPA `@Version`) to mutable aggregates edited by humans: `products`, `customers`, `suppliers`, `purchase_orders`, `sales_documents` (draft), `inventories`, `users`. Stock quantities are protected by ADR-012, not by `@Version`.
- **Consequences:** Updates carry the version (`If-Match`/body field); conflict → `CONCURRENT_MODIFICATION` (409).

## ADR-028 — Stock representation: ledger + maintained balance per lot
- **Status:** Accepted (derived from Spec §2.3 and Source Schema)
- **Decision:** `stock_movements` is the authoritative ledger (signed quantity in **base unit**). `lots.quantity_on_hand` is a **maintained balance** that must equal `SUM(stock_movements.quantity)` for the lot at all times. Product-level stock is `SUM(lots.quantity_on_hand)` over lots, filtered by sellability for *sellable* stock.
- **Reason:** Fast reads (FEFO, POS) without summing the ledger; ledger gives full history.
- **Consequences:** BR-STOCK-007 (reconciliation) is enforced by tests and a scheduled integrity check `[REC]`.

## ADR-029 — Authorization is permission-based, enforced in the backend
- **Status:** Accepted (project brief: "backend is the authoritative security boundary")
- **Decision:** Roles map to permission codes (`roles`, `permissions`, `role_permissions`); endpoints/services are guarded by permission (method security). Frontend hides UI elements as a convenience only.
- **Consequences:** `security.md`. Permission matrix validation: **OD-023**.

## ADR-030 — Testing against a real MySQL
- **Status:** ~~Proposed~~ → Accepted (user confirmation, 2026-10-20, Phase 0 gate)
- **Decision:** Integration and repository tests run against the **same MySQL major version as production** using Testcontainers, never an in-memory substitute (H2 differs in locking, `CHECK`, generated columns, triggers).
- **Consequences:** CI needs a container runtime. See `testing.md`.

## ADR-031 — Observability baseline
- **Status:** ~~Proposed~~ → Accepted (user confirmation, 2026-10-20, Phase 0 gate). JSON logs use Spring Boot's built-in structured logging (no extra encoder dependency).
- **Decision:** Structured (JSON) application logs with a per-request correlation id (`traceId`, echoed in error responses); security events logged at a dedicated logger; no sensitive data (passwords, tokens) in logs; Spring Boot Actuator `health` only, not exposed publicly.
- **Consequences:** `architecture.md` §9.

## ADR-032 — Tool and runtime versions (resolves OD-024)
- **Status:** Accepted (user decision, 2026-10-20)
- **Context:** OD-024 asked to confirm the Java, Spring Boot, MySQL, Node.js and CI versions before Phase 0. Spring Boot 3.5 open-source support ended mid-2026.
- **Decision:**
  | Concern | Version |
  |---|---|
  | Java | 21 LTS (Temurin/OpenJDK) |
  | Spring Boot | 4.x line; pinned to **4.1.1** at Phase 0 (latest stable on 2026-10-20) |
  | Build | Maven (wrapper committed, `./mvnw`) |
  | Database | MySQL **8.4 LTS** (Testcontainers image `mysql:8.4`) |
  | Node.js | 24 LTS; npm with committed lockfile |
  | CI | GitHub Actions (`.github/workflows/`) |
  | Architecture tests | ArchUnit (ADR-006) |
- **Alternatives:** Java 25 (not installed locally); Spring Boot 3.5 (support window closed); GitLab CI.
- **Consequences:** `backend.md` §1 says "4.x" instead of "3.x". Patch upgrades within these lines need no new ADR; a major or LTS change does. Library versions are pinned in `pom.xml` / `package-lock.json`.

## ADR-033 — UI languages, currency, locale and business time zone (resolves OD-008)
- **Status:** Accepted (user decision, 2026-10-20)
- **Context:** OD-008 asked which currency, locale, UI languages and business time zone to use. The Spec is French; the schema hints at Algeria (wilaya, NIF, VAT 9 %); the prototype used EUR.
- **Decision:**
  - **UI languages:** French (default), Arabic, English. Arabic is **right-to-left**: the root element's `dir` and `lang` follow the active language, and layouts use logical CSS properties (`ms-`/`me-`, `start`/`end`) — never `left`/`right`.
  - **Currency:** Algerian dinar, ISO code **DZD**, 2 decimal places.
  - **Formatting locale:** `fr-DZ` for numbers, dates and currency regardless of UI language `[REC]`, so amounts read the same for every user (Latin digits); can be revisited per document in Phase 10. Times use a **24-hour clock** even though the `fr-DZ` locale data defaults to AM/PM (user decision, 2026-10-21).
  - **Business time zone:** `Africa/Algiers` (used for "today", expiry and document year; storage stays UTC per ADR-010).
  - These values are **settings with defaults**, not literals scattered in code (CLAUDE.md §11): backend `ims.business.*` configuration in Phase 0, moved to/overridable by `app_settings` in Phase 1. *Done 2026-10-21 by ADR-044: rows in `app_settings`, only the locale editable.*
- **Alternatives:** EUR/fr-FR (prototype); French only (no RTL).
- **Consequences:** The i18n layer (i18next) with `fr`, `ar`, `en` catalogues and RTL switching is built in Phase 0; every UI string is externalised. Document templates (Phase 10) must support Arabic text. DR-014 currency/locale part is resolved.

## ADR-034 — Backend module list (amends ADR-006)
- **Status:** Accepted (user decision, 2026-10-20)
- **Context:** ADR-006 listed 18 modules (`product`, `user`, `supplier`, `customer`, `returns`, `payment`, …) while `backend.md` §2 and `architecture.md` §2.2/§3 use a coarser list on which the dependency rules are written.
- **Decision:** The module packages are those of `backend.md` §2: `common`, `auth`, `users`, `catalog`, `inventory`, `purchasing`, `sales`, `finance`, `reporting`, `audit`, `numbering`, `settings`, `printing`, `backup`. Suppliers live in `purchasing`; customers and returns in `sales`; payments in `finance`. Base package **`com.imsvet.ims`** (Maven groupId `com.imsvet`, artifactId `ims`).
- **Consequences:** ADR-006's module list is superseded by this ADR; its reasoning (lot merged into inventory, numbering separate, CreditLimitPort) still holds. ArchUnit tests encode `architecture.md` §3.

## ADR-035 — Test MySQL container lifecycle (resolves OD-030)
- **Status:** Accepted (user decision, 2026-10-20, Phase 0 close)
- **Context:** The Testcontainers MySQL container is a bean of `TestcontainersConfiguration`, so Spring's test-context cache gives one container (~10 s start) per distinct test-context configuration. A warm `./mvnw verify` took 45 s with two contexts; the reported 7 min 46 s was a cold run (dependency download and first `mysql:8.4` pull).
- **Decision:** Keep **one container per Spring test-context configuration** (OD-030 option A). Classes that share a configuration share the context and its container; each new configuration gets a fresh, empty database. No JVM-wide shared container and no Testcontainers reuse in CI.
- **Alternatives:** (B) one container per JVM shared by all contexts — faster, but needs a cross-context cleanup convention and a dedicated container for empty-DB migration tests; (C) opt-in reuse on developer machines — leftovers between runs would make local results differ from CI.
- **Consequences:** Strongest isolation; "Flyway on an empty DB" holds by construction. Build time grows ~10 s per new test-context configuration, so tests should reuse the existing configurations (`@SpringBootTest` + `TestcontainersConfiguration`, or the `RANDOM_PORT` variant) rather than add per-class variations. Revisit (new ADR) if the integration suite grows past ~4 configurations or the warm build becomes too slow. `testing.md` §1.7 and §3.

## ADR-036 — Authentication with server-side sessions (resolves OD-001)
- **Status:** Accepted (user decision, 2026-10-20, Phase 1 preflight)
- **Context:** OD-001 offered (A) JWT access + refresh tokens or (B) server-side session cookie. Single site, one bundled SPA, one application instance (A-001); BR-SEC-004 requires that a disabled user stops working.
- **Decision:** Option B. Opaque session id in a `HttpOnly; Secure; SameSite=Strict` cookie; sessions stored in MySQL with Spring Session JDBC (tables created by a Flyway migration, never by Spring's initializer). The session holds only the user id; the user's active flag, role and permissions are re-read on every request, so deactivation and permission changes take effect on the next request. New session id at login (fixation protection); logout and user deactivation delete the user's sessions. Idle timeout 30 min, absolute timeout 10 h, both configuration properties. CSRF protection on every state-changing request, including login: token in a readable `XSRF-TOKEN` cookie, echoed by the SPA in the `X-XSRF-TOKEN` header.
- **Alternatives:** (A) JWT — needs a signing key, a refresh endpoint and a refresh-token table; revocation only at access-token expiry.
- **Consequences:** No `/api/auth/refresh`; no signing key to manage; SD-111 reduced to the session tables. `security.md` §2.3, `api.md` §1/§4.1/§5.1, `frontend.md` §8–§10, `backend.md`.

## ADR-037 — Role × permission matrix (resolves OD-023)
- **Status:** Accepted (user decision, 2026-10-20, Phase 1 preflight)
- **Decision:** The catalogue and matrix of `security.md` §4 are adopted as written. All permission codes and the four role mappings are seeded by a Flyway migration in Phase 1, including codes whose endpoints arrive in later phases. Codes are never created at runtime; the Administrator may change role mappings through `PUT /api/roles/{id}/permissions` (`role.manage`, audited).
- **Consequences:** The endpoint × role test matrix (BR-SEC-002) is generated from this table; later phases only add guarded endpoints.

## ADR-038 — Login throttling (BR-SEC-005)
- **Status:** Accepted (user decision, 2026-10-20, Phase 1 preflight)
- **Decision:** Failed logins are counted per username + client IP in memory (single instance, A-001). After `login_max_failures` (5) failures within `login_window_minutes` (15), further attempts for that pair are refused with `RATE_LIMITED` (429) for `login_lockout_minutes` (15) — without checking the password. A successful login resets the counter. The three values are `app_settings` keys (seeded), not code constants. Accounts are never locked in the database.
- **Alternatives:** account lock on the user row (lets anyone lock any account by guessing usernames); progressive delays.
- **Consequences:** Counters reset on restart (accepted). No login-attempt table (SD-111).

## ADR-039 — Password hashing and policy
- **Status:** Accepted (user decision, 2026-10-20, Phase 1 preflight)
- **Decision:** bcrypt with cost 12 through Spring Security's `DelegatingPasswordEncoder` (algorithm prefix stored, upgradable at next login; no extra library). Policy: minimum 12 characters, no composition rules, rejected if in a bundled common-password list, no forced periodic rotation; changing one's password requires the current one. An Administrator reset issues a one-time temporary password; `users.must_change_password` (SD-114) then restricts the user to `GET /api/auth/me`, `POST /api/auth/change-password` and `POST /api/auth/logout` — any other request returns `PASSWORD_CHANGE_REQUIRED` (403).
- **Alternatives:** Argon2id (needs BouncyCastle).
- **Consequences:** `security.md` §2.2. `PASSWORD_CHANGE_REQUIRED` is added to the `api.md` §3 catalogue together with the `ErrorCode` constant in the Phase 1 login slice (the catalogue test keeps both identical).

## ADR-040 — Bootstrap of the first administrator
- **Status:** Accepted (user decision, 2026-10-20, Phase 1 preflight)
- **Decision:** At startup, if the `users` table is empty and `IMS_BOOTSTRAP_ADMIN_USERNAME` and `IMS_BOOTSTRAP_ADMIN_PASSWORD` are set, the application creates one Administrator with `must_change_password = true`, logs a security event (never the password) and writes an audit row. If users already exist, the variables are ignored. Nothing is hard-coded in a migration.
- **Consequences:** The operator removes the variables after first start (`README.md`, `.env.example`).

## ADR-041 — Usernames are case- and accent-insensitive (resolves OD-025 for `username` only)
- **Status:** Accepted (user decision, 2026-10-20, Phase 1 preflight)
- **Decision:** `users.username` keeps the database default collation `utf8mb4_0900_ai_ci`: `Ahmed`, `ahmed` and `ahméd` are the same username, both for uniqueness and at login. The username is stored as entered.
- **Consequences:** A repository test documents the behaviour. OD-025 remains open for `sku`, `lot_number` and other codes (Phase 3).

## ADR-042 — Administration cannot be locked out (resolves OD-031)
- **Status:** Accepted (user decision, 2026-10-20)
- **Decision:** A user, role or role-permission change that would leave **no active user holding both `user.manage` and `role.manage`** is refused with `INVALID_STATE_TRANSITION` (409, as in the `api.md` §3 catalogue; corrected 2026-10-21). A user cannot deactivate their own account. The check runs in the same transaction as the change: all role rows are locked `FOR UPDATE` in id order first (serialising such changes), the change is applied and flushed, then active holders of both permissions are counted.
- **Alternatives:** no guard with a documented SQL recovery (B); both (C).
- **Consequences:** No new error code. Covered by user-administration tests (`testing.md` §7). `security.md` §3, `api.md` §4.2.

## ADR-043 — MySQL servers run with `log_bin_trust_function_creators=1`
- **Status:** Accepted (user decision, 2026-10-21, Phase 1 slice 1)
- **Context:** MySQL 8.4 enables binary logging by default; creating a trigger (ADR-017) then requires the SUPER privilege or `log_bin_trust_function_creators=1`. The first trigger migration (`V01_005`) failed with error 1419 under the normal database user.
- **Decision:** Every MySQL server of the project — test containers, development, production — runs with `log_bin_trust_function_creators=1` (server option or `my.cnf`). Binary logging stays enabled (needed for point-in-time recovery, OD-021); no account receives SUPER.
- **Alternatives:** (B) a privileged migrator account for Flyway (two datasources, root in tests); (C) no triggers (weakens BR-AUDIT-002 and later the stock ledger guard).
- **Consequences:** `TestcontainersConfiguration` starts MySQL with the option; `README.md` prerequisites and `database-migration.md` §9 make it mandatory. The relaxed check concerns stored routines and triggers; migrations create only the append-only triggers of ADR-017, since business logic stays out of the database (ADR-007).

## ADR-044 — Settings API, value formats and business settings
- **Status:** Accepted (user decision, 2026-10-21, Phase 1 settings slice)
- **Context:** `api.md` listed `PUT /api/settings` without semantics; BR-LOT-010 gave `expiry_alert_days` only as "positive integers"; ADR-033 moves currency, locale and time zone to `app_settings` in Phase 1 without saying whether they are editable.
- **Decision:**
  - Endpoints: `GET /api/settings/public` (authenticated), `GET /api/settings`, `GET /api/settings/{key}`, `PUT /api/settings/{key}` (`settings.manage`). **No batch `PUT /api/settings`.**
  - Keys exist only through migrations; an unknown key is `NOT_FOUND`. Each key has a format; the value is validated, stored in canonical form and every real change is audited `SETTING_CHANGE` (an unchanged value is not).
  - Formats: `expiry_alert_days` = **exactly three distinct positive integers, strictly decreasing** (notice, warning, critical), e.g. `60,30,15`; `default_vat_rate` = 0–100 with at most two decimals, stored with two; `login_*` = integers ≥ 1; `locale` = a language tag the JVM supports; `currency` = ISO 4217; `business_time_zone` = an IANA zone.
  - `currency`, `locale`, `business_time_zone` are `app_settings` rows (seeded `DZD`, `fr-DZ`, `Africa/Algiers`); the `ims.business.*` configuration is removed. **Only `locale` is editable**; `currency` and `business_time_zone` are read-only (`VALIDATION_FAILED`, code `READ_ONLY`) because changing them would relabel stored amounts or move "today" for expiry and document years — they change only through a reviewed migration.
  - The public view holds the thresholds, default VAT, currency, locale and time zone — never the security settings.
  - The application refuses to start when stored settings and definitions disagree (missing, unknown or malformed key).
- **Alternatives:** batch update in one transaction; editable currency and time zone; keeping the business values in configuration.
- **Consequences:** `api.md` §4.14, BR-LOT-010, `database.md` §3.1; `BusinessSettings` (common port) is implemented by the `settings` module. Phase 4 adds the formats `boolean` (`opening_stock_open`, ADR-082) and `HH:mm` (`reorder_job_time`, `integrity_check_time`, ADR-078, ADR-086), all editable by `settings.manage`.

## ADR-045 — User administration rules
- **Status:** Accepted (user decision, 2026-10-21, Phase 1 user-administration slice)
- **Decision:**
  - A user created with `initialPassword` must change it at first login (`must_change_password = true`), like after a reset.
  - `POST /api/users/{id}/reset-password` generates the temporary password: 16 random characters from an alphabet without look-alikes (no `0/o`, `1/l/i`), shown as four groups (`k7mq-3xht-9pwd-r2va`, ≈ 79 bits). It is returned once and never stored in clear, logged or audited.
  - The username is immutable; `PUT /api/users/{id}` changes full name, e-mail and role, with `version` (ADR-027).
  - A password reset and a deactivation end the user's sessions after commit; a role change does not (permissions are re-read on every request, ADR-036).
  - Username and e-mail are unique case- and accent-insensitively (ADR-041); a duplicate is `DUPLICATE_RESOURCE` with `context.field`.
- **Consequences:** `api.md` §4.2, `security.md` §2.2. Sessions are ended through the `UserSessions` port implemented by `auth`.

## ADR-046 — The audit viewer names the acting user
- **Status:** Accepted (user decision, 2026-10-21, Phase 1 frontend-admin slice; option C of A id only / B client-side lookup / C server-side)
- **Context:** `audit_log` stores `user_id`. A client-side lookup through `GET /api/users` would fail for Accountants, who hold `audit.read` but not `user.read`.
- **Decision:** `GET /api/audit-logs` returns `username` and `userFullName` with each entry (`null` for system actions). `audit` defines a `UserDirectory` port; `users` implements it (dependency inversion: `audit` must not depend on `users`). One batched query per page; no schema change. Usernames are immutable (ADR-045), so the name is the one that acted.
- **Consequences:** `api.md` §4.14; audit viewer in the frontend.

## ADR-047 — Products have explicit target species (resolves OD-018, species part)
- **Status:** Accepted (user decision, 2026-10-21, Phase 2 preflight)
- **Context:** DR-010: products relate to species only through withdrawal periods, so products for non-food animals (dogs, cats) could never be found by species; FR-SALE-002 requires search by species.
- **Decision:** New table `product_target_species (product_id, species_id)` (SD-106). The species filter of `GET /api/products` matches a product whose **target species or withdrawal-period species** include it. Target species are maintained with `PUT /api/products/{id}/target-species` (`product.update`).
- **Alternatives:** withdrawal periods only (A); drop the criterion (C, contradicts FR-SALE-002).
- **Consequences:** `database.md` §3.2, `api.md` §4.3, `frontend.md` §4.1; DR-010 resolved.

## ADR-048 — Product search technique (resolves OD-018, technique part)
- **Status:** Accepted (user decision, 2026-10-21, Phase 2 preflight)
- **Decision:** `q` matches **anywhere** (`LIKE '%term%'`, wildcards escaped) in the commercial name, the SKU and the names of the product's active ingredients, case- and accent-insensitively through the `utf8mb4_0900_ai_ci` collation. Results are ordered: exact SKU match, name starting with the term, other matches, then by name. A performance test on a **reference dataset of 50,000 products** (`architecture.md` §14) must keep every search under 1 s (NFR-002). `FULLTEXT` is adopted only by a new ADR if that test fails.
- **Alternatives:** prefix-only `LIKE 'term%'` (misses words inside names); `FULLTEXT`/ngram (rows searchable only after commit, noisy ranking for short Latin words).
- **Consequences:** `database.md` §5, `api.md` §4.3/§5.2, `testing.md`; the frontend debounces input (≈250 ms, `frontend.md` §15).

## ADR-049 — Prices per base unit have 4 decimals (resolves OD-022, price part)
- **Status:** Accepted (user decision, 2026-10-21, Phase 2 preflight)
- **Decision:** Every price **per base unit** is `DECIMAL(14,4)` (SD-104): `products.purchase_price`, `products.sale_price`, `customer_product_prices.unit_price`, `supplier_price_history.unit_price` (`lots.unit_cost` already is). Prices of a packaging unit (`product_units.sale_price`) and every money amount and total stay `DECIMAL(14,2)`. JSON strings carry the stored scale. Rounding of line and document totals remains **OD-007** (Phase 5).
- **Alternatives:** 2 decimals (cannot store e.g. 0.0125 DA per tablet).
- **Consequences:** `database.md` §1/§3.2/§3.3, `api.md` §1.

## ADR-050 — Divisible and whole-unit products (resolves OD-022, quantity part)
- **Status:** Accepted (user decision, 2026-10-21, Phase 2 preflight)
- **Decision:** `products.is_divisible BOOLEAN NOT NULL` (SD-115), required when a product is created. Divisible base units (ml, g) take quantities with up to 3 decimals; non-divisible base units (tablet, syringe, dose) take **whole numbers only** — for every stock quantity and threshold of that product, and (Phase 3) packaging-unit factors must then be whole numbers too. Columns stay `DECIMAL(…,3)`; the services enforce the whole-number rule.
- **Alternatives:** 3 decimals for every product ("2.5 tablets" accepted).
- **Consequences:** BR-UNIT-002, BR-PROD-005, `database.md` §3.2; DR-012 resolved apart from OD-007.

## ADR-051 — SKUs are case- and accent-insensitive (resolves OD-025 for `sku` only)
- **Status:** Accepted (user decision, 2026-10-21, Phase 2 preflight)
- **Decision:** `products.sku` keeps the default `utf8mb4_0900_ai_ci` collation: `amx-10` and `AMX-10` are the same SKU, for uniqueness and for search. Reference-data names follow the same collation. OD-025 stays open for `lot_number` and other codes (Phase 3).
- **Consequences:** BR-PROD-001 test covers case and accent variants.

## ADR-052 — Reference data seed
- **Status:** Accepted (user decision, 2026-10-21, Phase 2 preflight); values given by the user from the legacy schema file.
- **Decision:** Seeded in **English**, in a data migration separate from the table migrations, exactly: animal species (7) Bovine, Ovine, Caprine, Poultry, Equine, Canine, Feline; therapeutic categories (5) Antibiotic, Antiparasitic, Vaccine, Anti-inflammatory, Vitamin / Supplement; dosage forms (5) Injectable, Powder, Tablet, Solution, Pour-on; manufacturers and active ingredients start empty. How the screen shows the seeded values in French and Arabic: **ADR-054** (was OD-032).
- **Consequences:** `database.md` §8.

## ADR-053 — Deleting reference data
- **Status:** Accepted (user decision, 2026-10-21, Phase 2 preflight)
- **Decision:** `DELETE` on a reference entry (category, manufacturer, dosage form, active ingredient, species) is allowed with `reference.manage` when nothing references it; otherwise `RESOURCE_IN_USE` (409). Entries are physical rows of lookup lists, not master data, so the no-hard-delete rule (`database.md` §1) does not apply to unused ones.
- **Consequences:** `api.md` §4.3 (it listed only GET/POST/PUT while describing a refused delete).

## ADR-054 — Seeded reference values have codes translated by the UI (resolves OD-032)
- **Status:** Accepted (user decision, 2026-10-21, Phase 2 catalog-reference slice); French and Arabic labels confirmed by the user.
- **Decision:** Seeded species, therapeutic categories and dosage forms carry a stable `code` (`BOVINE`, `ANTIBIOTIC`, `POUR_ON`, …); administrator entries have none. The UI shows a seeded entry through its code in the current language — labels live in the frontend language files (`reference.<list>.<CODE>`), not in the database — and shows an administrator entry exactly as typed. Seeded entries can be neither renamed nor deleted (`INVALID_STATE_TRANSITION`, 409). A backend test fails the build if a seeded code lacks a French, Arabic or English label.
- **Labels:** species Bovin/أبقار, Ovin/أغنام, Caprin/ماعز, Volaille/دواجن, Équin/خيول, Canin/كلاب, Félin/قطط; categories Antibiotique/مضاد حيوي, Antiparasitaire/مضاد للطفيليات, Vaccin/لقاح, Anti-inflammatoire/مضاد للالتهاب, Vitamine / Complément/فيتامين / مكمّل غذائي; dosage forms Injectable/قابل للحقن, Poudre/مسحوق, Comprimé/أقراص, Solution/محلول, Pour-on/سكب على الظهر (English labels = stored names).
- **Consequences:** `database.md` §3.2 (`code` columns), `api.md` §4.3, `frontend.md` §3.1.

## ADR-055 — Expiry boundary and month-only expiry dates (resolves OD-009)
- **Status:** Accepted (user decision, 2026-10-22, Phase 3 preflight; #1 A, #2 A)
- **Decision:** A lot is **sellable through its expiry date** and expired from the next day: expired ⇔ `expiry_date < business date`, the business date being today in the `business_time_zone` setting (`Africa/Algiers`) read from the injected `Clock` (`BusinessCalendar`). Stock-entry screens accept a full date or a month `MM/YYYY`; a month is stored as the **last day of that month** (`10/2027` → `2027-10-31`). The API always takes and returns full ISO dates.
- **Alternatives:** expired on the expiry date itself (B); full dates only.
- **Consequences:** BR-LOT-003, BR-STOCK-011, `database.md` §2.2/§6, glossary; DR-017 resolved with ADR-059. Tests cover yesterday / today / tomorrow and a business date that differs from the UTC date.

## ADR-056 — Expiry alert levels (BR-LOT-010)
- **Status:** Accepted (user decision, 2026-10-22, Phase 3 preflight; #3 A)
- **Decision:** `daysLeft = expiry_date − business date`. Level: **expired** when `daysLeft < 0` (ADR-055), else **critical** when `daysLeft ≤ critical`, **warning** when `≤ warning`, **notice** when `≤ notice`, otherwise none. The three thresholds are read from the `expiry_alert_days` setting (`notice,warning,critical`, default `60,30,15`, ADR-044) at each evaluation and are never hard-coded; limits are inclusive (a lot with exactly 15 days left is critical). The level is computed in the service on every query, not stored and not computed by a database view (DR-019).
- **Alternatives:** exclusive limits (B).
- **Consequences:** `api.md` §4.4/§5.3, W12, `frontend.md` §4.2 (badges and dashboard widget use the levels returned by the API).

## ADR-057 — Barcodes per packaging unit (resolves OD-019)
- **Status:** Accepted (user decision, 2026-10-22, Phase 3 preflight; #4 A)
- **Decision:** `product_barcodes.unit_id BIGINT NULL` (SD-105); `NULL` means the base unit. The unit must belong to the barcode's product: composite foreign key `(unit_id, product_id) → product_units(id, product_id)` plus a service check. A scan (`GET /api/products/by-barcode/{code}`) returns the product, the matched unit (name, factor, sale price as defined by ADR-063, or the base unit) and a quantity of **1 of that unit**.
- **Alternatives:** product-level barcodes only, unit chosen at every scan (B).
- **Consequences:** `database.md` §3.2, `api.md` §4.3, BR-UNIT-005, `frontend.md` §4.1; DR-011 resolved.

## ADR-058 — Collation of lot numbers and barcodes (resolves OD-025)
- **Status:** Accepted (user decision, 2026-10-22, Phase 3 preflight; #5 A, #6 A)
- **Decision:** `lots.lot_number` keeps the default `utf8mb4_0900_ai_ci`: `L2407a` and `L2407A` (and accent variants) are the same lot number, for the lot identity `UQ(product_id, lot_number)` (ADR-070) and for search. `product_barcodes.code` uses **`utf8mb4_bin`**: exact, case-sensitive match for uniqueness and scan lookup. With `username` (ADR-041) and `sku` (ADR-051) every code column existing through Phase 3 is decided; any later code column states its collation in its migration and gets a collation test (ADR-024).
- **Consequences:** `database.md` §3.2/§3.4, `database-migration.md` row 28, `testing.md` §2; DR-024 resolved.

## ADR-059 — Lot status: decisions stored, expired and depleted computed (resolves OD-027, status part)
- **Status:** Accepted (user decision, 2026-10-22, Phase 3 preflight; #7 A)
- **Decision:** `lots.status` stores only what a person decides: `CHECK (status IN ('active','blocked','recalled'))`. **Expired** (ADR-055) and **depleted** (`quantity_on_hand = 0`) are computed whenever a lot is read or used, never stored, so no daily job is needed and they cannot drift from the calendar or the balance. The API returns the effective status with the priority **recalled > blocked > expired > depleted > active**. A depleted lot that receives stock again is active by itself (customer return, inventory); a blocked lot stays blocked. Sellable ⇔ stored `active` ∧ not expired ∧ quantity > 0 (BR-STOCK-011).
- **Alternatives:** daily job writing `expired` and movements writing `depleted` (B); both (C).
- **Consequences:** `database.md` §2.2/§3.4 (CHECK reduced, SD-116), BR-LOT-009, `domain-model.md` §5.1, W12 (no materialisation); DR-017 resolved.

## ADR-060 — Recall is final; unblocking needs `lot.unblock` (resolves OD-027, transitions part)
- **Status:** Accepted (user decision, 2026-10-22, Phase 3 preflight; #8 A, #9 A)
- **Decision:** Stored transitions: `active → blocked` (`lot.block`, reason, audited `LOT_BLOCK`), `blocked → active` (`lot.unblock`, mandatory reason, audited `LOT_UNBLOCK`), `active | blocked → recalled` (`lot.recall`, reason, audited `LOT_RECALL`). **Recalled is terminal**: it can be neither unblocked nor un-recalled by anyone; the remaining stock is handled as unsellable goods (supplier return in Phase 5, adjustment in Phase 4). Any other request is `INVALID_STATE_TRANSITION`; repeating a recall returns the same report (W6). `lot.unblock` is held by Administrator and Pharmacist (ADR-037). Block and unblock are allowed on an expired or depleted lot (they change only the stored status).
- **Alternatives:** an Administrator may lift a recall (B); unblock reserved to Administrators (B).
- **Consequences:** BR-LOT-009, `domain-model.md` §5.1, `api.md` §4.4.

## ADR-061 — No `unit_conversion` movement type (DR-018)
- **Status:** Accepted (user decision, 2026-10-22, Phase 3 preflight; #10 A)
- **Decision:** Stock is kept in base units only, so the legacy `unit_conversion` value is **removed** from the allowed `stock_movements.movement_type` values (SD-118). The table itself is created in Phase 4.
- **Consequences:** `database.md` §3.4, `database-migration.md` §4, BR-STOCK-005; DR-018 resolved.

## ADR-062 — Lot ↔ product consistency in the database (SD-103)
- **Status:** Accepted (user decision, 2026-10-22, Phase 3 preflight; #11 A)
- **Decision:** `lots` gets `UNIQUE (id, product_id)` when it is created (Phase 3). Every later table holding both a lot and a product references `(lot_id, product_id) → lots(id, product_id)`: `stock_movements` (Phase 4), `goods_receipt_lines` (Phase 5), `sales_document_lines` and return lines (Phases 7–8). Services also check and answer `LOT_PRODUCT_MISMATCH` (422) before the database would refuse. In Phase 3 the mismatch test exercises the service check (FEFO preview and lot queries by product); the database-level test arrives with the first referencing table in Phase 4.
- **Alternatives:** service check only (B).
- **Consequences:** BR-LOT-007, `database.md` §3.4; SD-103 Accepted; DR-008 resolved.

## ADR-063 — Sale price of a packaging unit
- **Status:** Accepted (user decision, 2026-10-22, Phase 3 preflight; #12 A)
- **Decision:** `product_units.sale_price DECIMAL(14,2) NULL` is optional. When empty, the unit's price is **derived** as `factor_to_base × products.sale_price`; the API returns it as `effectiveSalePrice` (4 decimals, half-up, informational) next to the stored `salePrice`. Setting, changing or clearing a stored unit price needs `price.edit` (`ACCESS_DENIED`, nothing saved) and is audited `PRICE_CHANGE` with old and new values (BR-PROD-006); a price given when the unit is created is not a change and is not audited (as for products). How unit prices combine with customer prices and categories, and the rounding of document lines, remain **OD-006** and **OD-007**.
- **Alternatives:** always derived, no stored price (B).
- **Consequences:** `api.md` §4.3, BR-PROD-006, `database.md` §2.3.

## ADR-064 — No lot creation in Phase 3
- **Status:** Accepted (user decision, 2026-10-22, Phase 3 preflight; #13 A, without a sample-data loader)
- **Decision:** Phase 3 offers **no way to create a lot**: no endpoint, no seed, no development loader. Lots will be created only by opening stock and inventory-found entries (Phase 4) and goods receipts (Phase 5), each supplying lot number and expiry (BR-LOT-001). Phase 3 tests insert lots directly in the test database; the lot screens and the dashboard widget show a proper empty state until Phase 4.
- **Alternatives:** opening stock brought forward (B); development-only sample loader (C).
- **Consequences:** `api.md` §4.4, `frontend.md` §4.2, `roadmap.md` Phase 3.

## ADR-065 — Unit conversions are exact
- **Status:** Accepted (user decision, 2026-10-22, Phase 3 preflight; #14 A)
- **Decision:** `quantity_base = quantity × factor_to_base` is computed with `BigDecimal` and **never rounded**: when the exact result needs more than 3 decimals (base-quantity scale) the request is refused with `VALIDATION_FAILED` (field code `SCALE`); for a non-divisible product (ADR-050) a non-whole result is refused (`WHOLE_NUMBER`), and its unit factors must be whole numbers. Factors keep `DECIMAL(14,4)`.
- **Alternatives:** round half-up to 3 decimals (B); factors limited to 3 decimals (C).
- **Consequences:** BR-UNIT-001/002, `api.md` §5.3.

## ADR-066 — The lot supplier column arrives with suppliers (Phase 5)
- **Status:** Accepted (user decision, 2026-10-22, Phase 3 preflight; #15 A)
- **Decision:** `lots` is created in Phase 3 without `supplier_id`; the column and its foreign key to `suppliers` are added by a Phase 5 migration together with the `suppliers` table (SD-117). No column exists without its constraint.
- **Consequences:** `database.md` §3.4.

## ADR-067 — Barcode format checks
- **Status:** Accepted (user decision, 2026-10-22, Phase 3 preflight; #16 A, check digit for EAN13 only)
- **Decision:** `code_type` ∈ `EAN13`, `CODE128`, `QR` (CHECK). Every code is non-blank and at most 64 characters. **EAN13**: exactly 13 digits with a valid check digit. **CODE128**: printable ASCII characters (32–126) only. **QR**: any text without control characters. No other check. Errors are `VALIDATION_FAILED` on field `code` (`FORMAT`, `CHECK_DIGIT`, `SIZE`).
- **Alternatives:** no format check (B).
- **Consequences:** BR-PROD-008, `api.md` §4.3.

## ADR-068 — Lot recall in Phase 3
- **Status:** Accepted (user decision, 2026-10-22, Phase 3 preflight; #17 A)
- **Decision:** From Phase 3, `POST /api/lots/{id}/recall` sets the status to `recalled`, stores reason, user and time (`blocked_reason/by/at`), writes `LOT_RECALL` in the same transaction, and returns the `RecallReport` with **empty** `buyers` and `anonymousSales` lists; the screen says that buyers are listed once sales exist (Phase 7). The report's lists are filled by Phase 7 without changing the contract.
- **Alternatives:** postpone recall to Phase 7 (B).
- **Consequences:** BR-LOT-008, W6, `api.md` §5.7, `frontend.md` §4.2.

## ADR-069 — User-selectable dark theme (Phase 13) and token-only colours `[REC]`
- **Status:** Accepted (user decision, 2026-10-22); a recommendation, not a Spec requirement.
- **Context:** DR-014 wrongly said the prototype has no dark theme. It defines a complete dark token set (system setting or `data-theme`), reproduced in `tokens.css`; there is no user switch and dark-theme contrast was never verified (`frontend.md` §6 holds the measured figures).
- **Decision:** (1) **Phase 13** delivers a theme switch (system / light / dark), following the operating system by default, remembered **per browser** in `localStorage` (every access in `try/catch`; blocked storage falls back to the system setting), applied before first paint so the wrong theme never flashes. No database or backend change unless the user later decides the choice must follow the user across devices. (2) From now on, through Phases 1–12, components use **design tokens only**, never hard-coded colours, so Phase 13 needs no screen rewrite.
- **Amendment (user decision, 2026-10-22):** the failing contrast pairs are fixed **now**, before Phase 3, not in Phase 13: a `--control` token (≥ 3:1 against bg and surface in both themes) borders inputs, selects and buttons while `--line` stays for decorative dividers; the light `--ok` and `--warn` text colours are darkened minimally so text on their 10 % tint reaches 4.5:1; alert borders stay decorative. An automated test (`src/styles/contrast.test.ts`) checks every token pair the components use, in both themes, so contrast cannot regress. Phase 13 then delivers only the switch, its persistence and the visual checks.
- **Consequences:** `roadmap.md` Phase 13, `frontend.md` §6, CLAUDE.md §6, DR-014.

## ADR-070 — Lot identity is (product, lot number)
- **Status:** Accepted (user decision, 2026-10-22, before migration V03_003); departs from the Source Schema (DR-036).
- **Context:** BR-LOT-002, `database.md` and `database-migration.md` carried the Source Schema key `UQ(product_id, lot_number, expiry_date)`, under which the same lot number with another expiry date silently becomes a second lot. `testing.md` already expected one lot per (product, lot number) with a consistent expiry.
- **Decision:** A lot is identified by **(product, lot number)**: `UQ(product_id, lot_number)` (lot number in the case- and accent-insensitive collation of ADR-058). Receiving or entering an existing lot number adds to that lot (find-or-create under lock, BR-LOT-002). An entry whose expiry date differs from the existing lot's is refused with `LOT_EXPIRY_MISMATCH` (422) and changes nothing; it is not a second lot. The check applies wherever lots are created (opening stock, receipts, returns), from the phase that creates them; Phase 3 has only the key and its constraint test (ADR-067).
- **Alternatives:** keep the Source key (product, lot number, expiry) (B).
- **Consequences:** BR-LOT-002, `requirements.md` (lot validations), `domain-model.md` (LotIdentity), `database.md` §2.2/§3.4, `database-migration.md` §7 and SD-119, `api.md` §3, `workflows.md` W2, OD-029 wording, ADR-058 wording; correcting a wrongly entered expiry date is a separate, audited action, to be specified with the first phase that creates lots.

## ADR-071 — Scan lookup takes the code as a query parameter (resolves OD-033)
- **Status:** Accepted (user decision, 2026-10-22, option A)
- **Context:** CODE128 and QR codes may contain `/` (ADR-067); in a path an encoded slash is rejected by the security firewall, so `GET /api/products/by-barcode/{code}` could not find such codes.
- **Decision:** The scan lookup is `GET /api/products/by-barcode?code=…`, same permission (`product.read`) and same response (`{product, unit, quantity: "1"}`, ADR-057). The code is compared exactly (ADR-058); a missing `code` is `VALIDATION_FAILED`. The frontend's scanner input in the product search uses this endpoint.
- **Alternatives:** forbid `/` in codes (B); keep the path form and use the `barcode` search filter for such codes (C).
- **Consequences:** `api.md` §4.3, `frontend.md` §4.1; tests with codes containing `/` and `%`.

## ADR-072 — Count lines are reconciled at the moment they are counted (resolves OD-012, concurrency)
- **Status:** Accepted (user decision, 2026-10-22, Phase 4 preflight; #1 A, refined by the user)
- **Context:** Sales and receipts continue while a count is open; a quantity frozen at count opening produces false variances (DR-026).
- **Decision:** Nothing is frozen. Opening a count records, per line, the lot balance at that moment (`theoretical_qty`, informational). When a user saves a counted quantity, the server locks the lot row and stores, with the counted quantity, the lot's balance at that moment (`balance_at_count`) and the id of the lot's last stock movement (`watermark_movement_id`, `NULL` when none), with user and time. **Variance = counted − balance at the watermark.** Saving the line again takes a new watermark. The line shows the variance and the movements on the lot since its watermark. Regularisation posts the variance as one `adjustment_inventory` movement on the **current** balance, so the sales and receipts after the watermark stay valid. If that would make the balance negative, regularisation is refused with `STOCK_INSUFFICIENT`, nothing is posted and the count stays open.
- **Alternatives:** freeze movements on counted lots (B); compare with the quantity at count opening (C).
- **Consequences:** `inventory_lines` gains `balance_at_count`, `watermark_movement_id`, `counted_at`, `counted_by` (SD-120); `variance_qty = counted_qty − balance_at_count`; BR-INV-001/005, new BR-INV-008, W5, `api.md` `CountDetail`, `frontend.md` §4.4; tests: a sale after the lot was counted (no false variance), a sale before it was counted, two edits of one line, a receipt during the count, concurrent regularisation and sale (CT-04), a regularisation that would go negative. DR-026 resolved.

## ADR-073 — Cycle-count scope: a category or a product list (resolves OD-012, scope)
- **Status:** Accepted (user decision, 2026-10-22, Phase 4 preflight; #2 A)
- **Decision:** A `full` count covers every lot with stock. A `cycle` count is scoped by **one** therapeutic category **or** an explicit list of products, stored on the count (`inventories.scope_category_id`, table `inventory_scope_products`, SD-113). Lines are created for the lots with stock of the products in scope. There is no location model, so no location scope.
- **Alternatives:** an explicit lot list (B); locations (C, not possible).
- **Consequences:** BR-INV-003, `database.md` §3.4, `api.md` `POST /api/inventory/counts` body.

## ADR-074 — Lots found during a count (resolves OD-012, found lots)
- **Status:** Accepted (user decision, 2026-10-22, Phase 4 preflight; #3 A)
- **Decision:** Lots without stock are not listed when a count opens. During an open count a user may add (a) an **existing lot** of a product in scope that has no line (its line starts like any other), or (b) a **lot unknown to the system**: product in scope, lot number, expiry date (BR-LOT-001; `MM/YYYY` allowed, ADR-055), unit cost ≥ 0 and counted quantity. The unknown lot is created only at regularisation, with an `adjustment_inventory` (+) movement of the counted quantity. If the lot number already exists for the product, the same expiry makes it case (a) and another expiry is `LOT_EXPIRY_MISMATCH` (ADR-070).
- **Alternatives:** existing lots only; unknown lots through opening stock (B).
- **Consequences:** `inventory_lines.lot_id` becomes `NULL` for a found lot until regularisation, with `product_id`, `found_lot_number`, `found_expiry_date`, `found_unit_cost` (SD-120); `api.md` `POST /api/inventory/counts/{id}/lines`.

## ADR-075 — Regularising closes the count (resolves OD-012, closing)
- **Status:** Accepted (user decision, 2026-10-22, Phase 4 preflight; #4 A)
- **Decision:** `POST …/regularize` (`inventory.validate`) posts, in one transaction, the accepted lines (all counted lines, or the `lineIds` given) with a non-zero variance, marks them regularised, and closes the count. Lines not accepted stay unregularised on the closed count. Lots are locked in ascending id order (ADR-012). Any failure (e.g. `STOCK_INSUFFICIENT`) rolls everything back and the count stays open. A count can be cancelled while open (no movement).
- **Alternatives:** partial regularisation with a separate close (B).
- **Consequences:** BR-INV-002/004, W5, domain-model §5.4.

## ADR-076 — Reorder trigger (resolves OD-013, trigger)
- **Status:** Accepted (user decision, 2026-10-22, Phase 4 preflight; #5 A)
- **Decision:** A product needs a proposal when it is not archived, its `reorder_point` is > 0 and its **sellable** quantity (BR-STOCK-011) is ≤ `reorder_point`. `min_stock` is not the trigger; it defines the *low* status (ADR-080).
- **Alternatives:** sellable ≤ `min_stock` (B).
- **Consequences:** BR-STOCK-010, W10.

## ADR-077 — Proposed reorder quantity (resolves OD-013, quantity)
- **Status:** Accepted (user decision, 2026-10-22, Phase 4 preflight; #6 A)
- **Decision:** Suggested quantity = `max_stock − sellable`, in base units, rounded **up** to a whole number for a non-divisible product (ADR-050). A product that needs a proposal but has no `max_stock` gets none and is reported as *thresholds incomplete* in the generation result. No formula is invented for it.
- **Alternatives:** reorder point + a configured target (B); up to max, else twice the reorder point (C).
- **Consequences:** BR-STOCK-010, `api.md` (generation result).
- **Amendment (user decision, 2026-10-23, after slice 10):** a product whose `max_stock` is at or below its `reorder_point` is not *thresholds incomplete* but *thresholds inconsistent*: it gets no proposal and is reported apart in the generation result (`thresholdsInconsistent`) and on the reorder page. No validation rule is added to the product form; whether to add one is OD-034.

## ADR-078 — When proposals are generated (resolves OD-013, schedule)
- **Status:** Accepted (user decision, 2026-10-22, Phase 4 preflight; #7 A)
- **Decision:** A daily job runs at the time of the new setting `reorder_job_time` (`HH:mm`, business time zone, default `06:00`), and `POST /api/inventory/reorder-proposals/generate` (`reorder.manage`) runs the same generation on demand. Sales never generate proposals.
- **Alternatives:** after every stock-reducing transaction (B); both (C).
- **Consequences:** `backend.md` §12, settings catalogue (ADR-044 formats gain `HH:mm`).

## ADR-079 — One pending proposal per product; supplier and open orders in Phase 5 (resolves OD-013, duplicates)
- **Status:** Accepted (user decision, 2026-10-22, Phase 4 preflight; #8 A)
- **Decision:** At most one `pending` proposal per product, enforced by a unique key on a generated column (SD-112). A new generation **updates** the pending proposal's quantities. Dismissing (`reorder.manage`) suppresses new proposals for that product until a generation sees its sellable quantity above the reorder point again; a rise and fall between two generations is not seen. `supplier_id` and the subtraction of open purchase-order quantities arrive in Phase 5 with suppliers and purchase orders, as does *convert to purchase order*.
- **Alternatives:** keep the first pending proposal unchanged (B); dismiss automatically when stock recovers (C).
- **Consequences:** `reorder_proposals` gains `updated_at`, `dismissed_at`, `dismissed_by`, `suppressed` (until recovery) and the generated `pending_product_id` with its unique key; no `supplier_id` before Phase 5 (SD-121); domain-model §5.6; DR-027 resolved.

## ADR-080 — Stock levels and status (BR-STOCK-011, new BR-STOCK-013)
- **Status:** Accepted (user decision, 2026-10-22, Phase 4 preflight; #9 A)
- **Decision:** Per product: **total** = Σ balance of all lots; **sellable** = Σ balance of lots stored active, not expired and with stock (BR-STOCK-011). Status: **out** when sellable = 0; **low** when 0 < sellable ≤ `min_stock`; otherwise **in stock**. Flag **below reorder** when sellable ≤ `reorder_point` (and the reorder point is > 0). Computed on every read with the current expiry policy.
- **Alternatives:** the same statuses on total stock (B).
- **Consequences:** `api.md` `StockLevel`, product list stock columns (ADR-089).

## ADR-081 — Movement types allowed by the database
- **Status:** Accepted (user decision, 2026-10-22, Phase 4 preflight; #10 A)
- **Decision:** The `stock_movements.movement_type` CHECK lists the ten decided types now: `purchase_receipt`, `sale`, `customer_return`, `supplier_return`, `adjustment_breakage`, `adjustment_expiry`, `adjustment_theft`, `adjustment_internal_use`, `adjustment_inventory`, `opening_stock`. Phase 4 produces only `opening_stock`, the four manual `adjustment_*` and `adjustment_inventory`. Cancellation and discard types are **not** added; they wait for OD-004 / OD-005 (SD-107, DR-002).
- **Alternatives:** each phase adds its own types (B).
- **Consequences:** `database.md` §3.4, BR-STOCK-005.

## ADR-082 — Opening stock only while the opening period is open
- **Status:** Accepted (user decision, 2026-10-22, Phase 4 preflight; #11 B)
- **Decision:** New boolean setting `opening_stock_open`, `true` by default until go-live, changed only by an Administrator (`settings.manage`) and audited `SETTING_CHANGE`. While it is `true`, `stock.adjust` holders may enter opening stock. While it is `false`, `POST /api/inventory/opening-stock` answers the business error `OPENING_STOCK_CLOSED` and the screen says to use an inventory count (ADR-074). Every opening-stock entry is audited `OPENING_STOCK`.
- **Alternatives:** any time (A); a separate Administrator-only permission (C).
- **Consequences:** settings catalogue (ADR-044 formats gain `boolean`), `api.md` §3 and §4.5, `security.md` §7, new BR-STOCK-014.

## ADR-083 — Content of an opening-stock entry
- **Status:** Accepted (user decision, 2026-10-22, Phase 4 preflight; #12 A)
- **Decision:** Product, lot number and expiry date are required (BR-LOT-001; a month `MM/YYYY` is the last day of that month, ADR-055); the quantity is given in a unit of the product (base unit by default) and converted exactly (ADR-065); the **unit cost** per base unit is required, ≥ 0 with at most 4 decimals, and the screen prefills it from the product's purchase price. An already expired lot is accepted (it exists physically) and the screen warns. One `opening_stock` (+) movement is posted.
- **Alternatives:** the unit cost taken from the product automatically (B).
- **Consequences:** `api.md` `POST /api/inventory/opening-stock`, BR-STOCK-014, `frontend.md` §4.3.

## ADR-084 — Opening stock on an existing lot
- **Status:** Accepted (user decision, 2026-10-22, Phase 4 preflight; #13 A)
- **Decision:** Find-or-create by (product, lot number) under lock (BR-LOT-002, ADR-070). Another expiry date → `LOT_EXPIRY_MISMATCH`. Same expiry: the quantity is added to the lot; a unit cost different from the lot's is refused (`VALIDATION_FAILED`, field `unitCost`, code `COST_MISMATCH`) until OD-029 decides cost on repeated entries (Phase 5); a recalled lot is refused (`LOT_RECALLED`); a blocked lot is accepted and stays blocked.
- **Alternatives:** add and keep the existing cost silently (B); weighted average (C).
- **Consequences:** BR-STOCK-014, `api.md` §4.5.

## ADR-085 — Manual adjustments
- **Status:** Accepted (user decision, 2026-10-22, Phase 4 preflight; #14 A)
- **Decision:** `POST /api/inventory/adjustments` (`stock.adjust`) takes a lot, a type among `adjustment_breakage`, `adjustment_expiry`, `adjustment_theft`, `adjustment_internal_use` (BR-STOCK-008), a quantity > 0 **in base units** (at most 3 decimals, whole for a non-divisible product) and a mandatory reason (≤ 500, BR-STOCK-006). It posts the negative movement, refuses more than the balance (`STOCK_INSUFFICIENT`), is allowed on blocked, recalled and expired lots (ADR-060), and is audited `STOCK_CORRECTION` with old and new quantity.
- **Alternatives:** quantity in any unit, converted (B).
- **Consequences:** W7, `api.md` §4.5, `frontend.md` §4.3.

## ADR-086 — Integrity check runs, storage and banner (BR-STOCK-007)
- **Status:** Accepted (user decision, 2026-10-22, Phase 4 preflight; #16 A)
- **Decision:** The balance is maintained by `StockService` in the movement's transaction, CHECK ≥ 0 being the safety net (ADR-028). The integrity check compares every lot balance with the sum of its movements. It runs nightly at the new setting `integrity_check_time` (`HH:mm`, business time zone, default `02:00`) and on demand (`POST /api/inventory/integrity-check`, `audit.read`). Every run is stored in `integrity_check_runs` (start, end, lots checked, mismatches with lot, balance and ledger sum, who triggered it or the system). A mismatch logs a CRITICAL `LEDGER_MISMATCH` security event. Users holding `audit.read` see a banner while the latest run has mismatches; it disappears after a clean run. The check reports only; it never corrects.
- **Alternatives:** log only, no stored result or banner (B).
- **Consequences:** new table `integrity_check_runs` (SD-122), `api.md` §4.5, `backend.md` §12, `security.md` §8, `frontend.md` (banner).

## ADR-087 — Separate migration and application database accounts (BR-SEC-006, now)
- **Status:** Accepted (user decision, 2026-10-22, Phase 4 preflight; #17 A)
- **Decision:** Flyway runs with a **migration account** (`IMS_DB_MIGRATION_USERNAME` / `IMS_DB_MIGRATION_PASSWORD`, DDL rights); the application connects with an **application account** (`IMS_DB_USERNAME` / `IMS_DB_PASSWORD`) that has `SELECT, INSERT, UPDATE, DELETE` on the application tables except `stock_movements` and `audit_log`, where it has only `SELECT, INSERT`, and no DDL. The grants are applied after every migration by a Flyway callback for each table, so a new table is covered automatically. The append-only triggers (ADR-017) stay and also stop privileged accounts. Testcontainers (tests, `spring-boot:test-run`, end-to-end) and CI create both accounts and run Flyway with the migration account and the application with the restricted one. Tests prove that the trigger refuses `UPDATE`/`DELETE` by the migration account (SQLSTATE 45000) and that the application account gets error 1142.
- **Alternatives:** triggers now, privileges in Phase 12 (B).
- **Consequences:** `.env.example`, `README.md`, `database-migration.md` §9, `security.md`, BR-STOCK-003, BR-SEC-006, roadmap Phase 12 (privileges already done). A missing account variable or one account for both roles stops the start before any connection, with a report naming the four variables and the README section (user request 2026-10-23).

## ADR-088 — `StockService` is the only writer of stock
- **Status:** Accepted (user decision, 2026-10-22, Phase 4 preflight; #18 A)
- **Decision:** The single gateway that inserts movements and changes lot balances is named `StockService` (module `inventory`), as in CLAUDE.md and `backend.md`; BR-STOCK-002 is corrected (it said `StockLedgerService`).
- **Consequences:** BR-STOCK-002.

## ADR-089 — Phase 4 also delivers the deferred stock-aware features
- **Status:** Accepted (user decision, 2026-10-22, Phase 4 preflight; #19 A)
- **Decision:** Phase 4 includes the items deferred to it by the Phase 2/3 scope splits: stock columns (sellable quantity, status, nearest expiry) and the `inStockOnly` filter on the product list, stock-aware substitutes, a low-stock dashboard widget, and the name of the user who blocked or recalled a lot (a read port of the `users` module for user names).
- **Alternatives:** stock pages only (B).
- **Consequences:** `roadmap.md` Phase 4, `api.md` §4.3, `frontend.md` §3.1/§4.1/§4.2, `architecture.md` §3 (module rule `inventory → users` for the name port only).

## ADR-090 — Stock conditions cross the catalog/inventory boundary as constant query text (option A)
- **Status:** Accepted (user decision, 2026-10-23, Phase 4 slice 6; option A with conditions)
- **Context:** The product list must filter by sellable stock inside the database (NFR-002, never by loading product ids), and the stock list must page and search by product name, SKU and category; `architecture.md` §3 rule 2 forbids a module to read another module's tables, and `v_product_stock` is not created (ADR-080).
- **Decision:** The catalog defines the port `ProductStock`; `inventory` implements it in **one named class**, `ProductStockConditions`, which returns boolean JPQL conditions on the product alias `p` (`EXISTS`/sums over `Lot`, `p.minStock`, `p.reorderPoint`). Conditions are **constant text**; every value (business date from the injected clock, active status, smallest quantity) is a **bind parameter** — no search term, id or date is ever concatenated. The catalog's search (`ProductSearchRepository`) appends them to its own constant text. The mirror direction needs no exception: `inventory` lists stock through the catalog port `ProductQueries` (`inventory → catalog` is allowed), passing its conditions. `ModuleSqlBoundaryTest` checks the string literals: the catalog never names inventory tables or entities, and inventory names catalog ones only in `ProductStockConditions`. Each condition uses one sellable subquery (two made the low-stock list ~0.55 s on 50,000 products).
- **Alternatives:** read-only joins in named query classes without a port (B); product list without stock (C).
- **Consequences:** `architecture.md` §3 rule 2 (exception), `api.md` §4.3/§4.5, `testing.md` §8.1 (in-stock searches; dataset committed and statistics refreshed). Measured 2026-10-23 on 50,000 products: every search under 1 s; the slowest typically 0.40–0.43 s, up to 0.81 s in a run on a loaded machine. A query planned against stale statistics (right after a large bulk load, before InnoDB refreshes them) can scan instead of using `idx_lots_fefo`; refreshed by `ANALYZE TABLE` (ADR-091).

## ADR-091 — Refresh table statistics after a bulk load
- **Status:** Accepted (user decision, 2026-10-23, Phase 4 slice 6 follow-up)
- **Context:** Right after a large bulk load (legacy import, opening stock in bulk, test datasets) InnoDB statistics can still describe the tables as they were before the load; MySQL then plans the product and stock searches against nearly empty tables and scans (a 50,000-product load examined 3.4 billion rows).
- **Decision:** `ANALYZE TABLE` on the loaded tables is an **operations step after every bulk load**, documented in the README and `database-migration.md` §9; no application code runs it for now. The 50,000-product performance test runs `ANALYZE TABLE` after generating its data (it already does, ADR-090).
- **Alternatives:** the import code runs it itself (later, when an import tool exists); rely on InnoDB's automatic recalculation (not timely enough).
- **Consequences:** README "Bulk loads", `database-migration.md` §9, `testing.md` §8.1.

---

# Part B — Open Decisions

Each Open Decision lists the question, the options, an optional author's leaning (**not a decision**), the phase it blocks, and where it matters. Until decided, **no code may depend on an assumption** about it.

## OD-001 — Authentication mechanism
- **Status:** Resolved 2026-10-20 → **ADR-036** (option B).
- **Question:** Stateless JWT (short-lived access token + rotating refresh token) or server-side session cookie (Spring Session)?
- **Options:** (A) JWT access token in memory + refresh token in `HttpOnly; Secure; SameSite=Strict` cookie, refresh tokens stored hashed in a new table; (B) opaque session id in `HttpOnly` cookie + CSRF protection, sessions in DB.
- **Leaning:** (A) for API-first design; (B) is simpler and gives instant revocation. Both satisfy FR-SEC-001.
- **Blocks:** Phase 1. **Related:** `security.md`, `api.md` §4.1, BR-SEC-001.

## OD-002 — Which sales documents move stock, and when
- **Question:** Does a **delivery note** deduct stock, or only an **invoice/receipt**? If an invoice is created from a delivery note, how is double deduction prevented? Are delivery notes part of lot recall?
- **Why it matters:** `sales_doc_type` includes `quote`, `delivery_note`, `invoice`, `receipt`, `credit_note`; Source views `v_sales_margin` and function `recall_lot` consider only `invoice` and `receipt`, so goods delivered on an un-invoiced delivery note would be missed by a recall.
- **Options:** (A) Only `invoice`/`receipt` deduct stock; delivery note is informational. (B) Delivery note deducts; invoice created from it does not (flag on lines). 
- **Blocks:** Phase 7. **Related:** BR-SALE-002, BR-SALE-005, BR-LOT-008, DR-004.

## OD-003 — Payment capture at the counter and the customer-balance definition
- **Question:** How are payments for **immediate (non-credit) sales** and **anonymous receipts** recorded? `customer_payments.customer_id` is `NOT NULL` and `sales_documents` carries no payment method, yet `cash_movements` need a method. And what exactly is a *customer balance* (credit invoices only? all invoices minus all payments?).
- **Why it matters:** `v_customer_balance` subtracts **all** `customer_payments` and **all** credit notes but adds only **credit** invoices → balance becomes wrong as soon as any cash sale is paid through `customer_payments` or a credit note is issued against a cash sale.
- **Options:** (A) Every sale creates a payment + allocation (uniform model; anonymous customer record allowed or `customer_id` nullable). (B) Immediate sales are "paid on confirmation" with a payment-method column on the document; `customer_payments` only for account settlements. 
- **Blocks:** Phase 7 (counter payment) and Phase 9. **Related:** BR-PAY-004…006, BR-CASH-003, DR-005, DR-006.

## OD-004 — Cancellation of confirmed documents
- **Question:** May a *confirmed* invoice/receipt be cancelled, or only reversed by a credit note? If cancellable, which movement type reverses the stock (the Source enum has no `sale_cancellation`/`receipt_cancellation`)?
- **Options:** (A) Cancellation allowed with permission `sale.cancel`; add movement type `sale_cancellation`. (B) No cancellation after confirmation; use credit note + customer return. 
- **Spec hint:** audit of "annulations de factures" (§2.7) implies cancellation exists.
- **Blocks:** Phase 7. **Related:** BR-SALE-014, ADR-008.

## OD-005 — Customer-return disposition and refund
- **Question:** (1) For `discard`, is there any stock movement? (2) How is a return on a **cash/anonymous** sale refunded (cash movement out)? (3) May restock be forced to `discard` automatically for expired/recalled lots?
- **Options (1):** none (return never enters stock) vs. `customer_return` (+) immediately followed by `adjustment_expiry/breakage` (−) for full traceability.
- **Blocks:** Phase 8. **Related:** BR-RET-002…006.

## OD-006 — Price resolution precedence
- **Question:** When several prices apply (product price per base unit, per-unit `product_units.sale_price`, `customer_product_prices`, `customer_categories.discount_pct`, manual line discount), what is the precedence and can they combine?
- **Blocks:** Phase 6/7. **Related:** BR-SALE-006, BR-SALE-007.

## OD-007 — Rounding, VAT computation, decimals
- **Question:** Round per line or per document? VAT per line or per rate group? Rounding mode (HALF_UP assumed)? Decimal places for unit prices (2 in schema) and quantities (3).
- **Blocks:** Phase 5 (PO totals). **Related:** ADR-016, BR-SALE-008, BR-PURCH-002.

## OD-008 — Currency, locale, UI language, business time zone
- **Status:** Resolved 2026-10-20 → **ADR-033**.
- **Question:** Currency and symbol (the design-system prototype formats EUR with `fr-FR`; the Source Schema uses *wilaya*, *NIF*, VAT 9 %, suggesting a different locale); UI language(s) (French per Spec; English strings in design system; Arabic/RTL?); business time zone.
- **Blocks:** Phase 0 (i18n structure) and Phase 10 (document templates).

## OD-009 — Expiry boundary and lot expiry convention
- **Status:** Resolved 2026-10-22 → **ADR-055** (sellable through the expiry date; `MM/YYYY` = last day of month) and **ADR-056** (alert levels).
- **Question:** Is a lot sellable **on** its expiry date (Source views use `expiry_date >= CURRENT_DATE`) or only before? Are expiry dates month-end ("EXP 10/2027" = last day of month)? Does the UI accept `MM/YYYY`?
- **Blocks:** Phase 3. **Related:** BR-LOT-003.

## OD-010 — FEFO override policy
- **Question:** The Spec says the system *proposes* FEFO. May a seller choose a different lot? With what permission/reason?
- **Leaning:** Allow only with permission `sale.override_fefo` and a recorded reason; always allow skipping non-sellable lots.
- **Blocks:** Phase 7. **Related:** BR-LOT-005.

## OD-011 — Credit-limit semantics
- **Question:** `credit_limit DEFAULT 0` — does 0 mean *no credit allowed* or *unlimited*? Are overdue invoices (`due_date`) a blocking condition? Is the limit checked against *balance + new invoice*?
- **Blocks:** Phase 9. **Related:** BR-PAY-005, BR-CUST-002.

## OD-012 — Inventory Count concurrency and scope
- **Status:** Resolved 2026-10-22 → **ADR-072** (concurrency, refined by the user), **ADR-073** (scope), **ADR-074** (found lots), **ADR-075** (closing).
- **Question:** While a count is open, are sales/receipts frozen, or is the variance computed against the theoretical quantity **at count time** (delta of movements since snapshot)? How is a *cycle* count scoped (category, shelf, ABC)? How are lots found physically but unknown to the system handled?
- **Blocks:** Phase 4. **Related:** BR-INV-005.

## OD-013 — Reorder proposal algorithm
- **Status:** Resolved 2026-10-22 → **ADR-076** (trigger), **ADR-077** (quantity), **ADR-078** (schedule), **ADR-079** (duplicates; supplier and open orders in Phase 5).
- **Question:** Which threshold triggers (`reorder_point` per Source view vs. `min_stock`)? Suggested quantity (e.g. `max_stock − current`)? Which supplier is proposed (cheapest in `supplier_price_history`)? Generated on every movement or by a scheduled job?
- **Blocks:** Phase 4/5. **Related:** BR-STOCK-010.

## OD-014 — Non-conforming goods at receipt
- **Question:** `goods_receipt_lines.is_conforming = false`: does the stock enter the lot as *blocked*, not enter at all, or enter normally and be returned later?
- **Blocks:** Phase 5. **Related:** BR-PURCH-006.

## OD-015 — Over-receipt and receipt without purchase order
- **Question:** May received quantity exceed the ordered quantity? May goods be received without a PO (`purchase_order_id` is nullable)?
- **Blocks:** Phase 5. **Related:** BR-PURCH-004.

## OD-016 — Supplier settlements
- **Question:** `supplier_payments.invoice_id` is single-valued: can one payment settle several invoices? How are *advances* (`invoice_id NULL`) later applied to invoices? Should a supplier return generate/link a supplier credit note (no link exists in the schema)?
- **Blocks:** Phase 9. **Related:** BR-PAY-009…011, DR-009.

## OD-017 — Document numbering policy
- **Question:** Number assigned at creation (draft) or at confirmation? Format (`KEY-YYYY-000001`)? Yearly reset? Gapless required for invoices only? Final key list (Source comment: `PO, INV, RCT, QUO, DN, CN`; code uses `PO, GR, SR, CR`)? Are cancelled numbers kept (assumed yes)?
- **Blocks:** Phase 5 (first numbered document). **Related:** ADR-011, BR-DOC-001.

## OD-018 — Search implementation (< 1 s) and species search
- **Status:** Resolved 2026-10-21 → **ADR-047** (species: explicit target species) and **ADR-048** (search technique).
- **Question:** Index-backed prefix `LIKE` vs. `FULLTEXT` (ngram parser) vs. external engine; accent-insensitive French/Arabic matching; how to search by **animal species** given the schema only links species to products through *withdrawal periods* (no "target species" relation for products without withdrawal data).
- **Options for species:** (A) use `product_withdrawal_periods` only; (B) add `product_species` table.
- **Blocks:** Phase 2 (catalogue search) and 7. **Related:** NFR-002, DR-010.

## OD-019 — Barcode per packaging unit
- **Status:** Resolved 2026-10-22 → **ADR-057** (`unit_id`, scan = 1 of that unit).
- **Question:** `product_barcodes` links to the product only. A box and a single blister of the same product normally have different barcodes: should `product_barcodes` get a `unit_id`? What quantity does one scan add?
- **Blocks:** Phase 3. **Related:** FR-PROD-003, DR-011.

## OD-020 — Printing approach
- **Question:** PDF generation library; thermal ticket width (58/80 mm) and method (PDF printed by browser vs. ESC/POS); templates; legal mentions on invoices; who owns document layout.
- **Blocks:** Phase 10. **Related:** NFR-003.

## OD-021 — Hosting, availability and backup policy
- **Question:** On-premises server vs. cloud; availability target; backup frequency/retention/RPO/RTO; where backups are stored; who restores; is the backup performed by the application or by an external operating-system job (the app then only records `backup_log`)?
- **Blocks:** Phase 12 (deployment); backup design touches Phase 11. **Related:** FR-AUD-002, NFR-004.

## OD-022 — Quantity and price precision per unit
- **Status:** Resolved 2026-10-21 → **ADR-049** (prices per base unit: 4 decimals) and **ADR-050** (divisible flag). Rounding stays OD-007.
- **Question:** May base-unit quantities be fractional (e.g. 2.5 ml) or whole only for discrete units (tablets)? Are 2 decimals enough for **price per base unit** (cheap units such as a 0.0125 tablet)? (The design-system hint says "Whole units only" for received quantity while the schema allows 3 decimals.)
- **Blocks:** Phase 2/3. **Related:** BR-UNIT-002, DR-012.

## OD-023 — Permission matrix
- **Status:** Resolved 2026-10-20 → **ADR-037** (matrix accepted as proposed).
- **Question:** Validate or amend the proposed role × permission matrix (`security.md` §4).
- **Blocks:** Phase 1.

## OD-024 — Tool and runtime versions
- **Status:** Resolved 2026-10-20 → **ADR-032**.
- **Question:** Confirm Java LTS version, Spring Boot line, MySQL version (8.4 LTS proposed), Node.js LTS, build/CI tooling.
- **Blocks:** Phase 0.

## OD-025 — Case/accent sensitivity of code columns
- **Status:** Resolved: `username` → **ADR-041** (2026-10-20), `sku` → **ADR-051** (2026-10-21), `lot_number` (insensitive) and barcodes (`utf8mb4_bin`) → **ADR-058** (2026-10-22).
- **Question:** Under `utf8mb4_0900_ai_ci`, are `LOT-a1` and `LOT-A1` the same lot? Should `sku`, `lot_number`, `username` use a case-sensitive collation?
- **Blocks:** Phase 3. **Related:** ADR-024.

## OD-026 — Company identity and document header data
- **Question:** The pharmacy's own legal identity (name, address, tax ids, logo, footer text) required on invoices is not in the schema. Store in `app_settings` or a dedicated `company_profile` table?
- **Blocks:** Phase 10.

## OD-027 — Lot status semantics
- **Status:** Resolved 2026-10-22 → **ADR-059** (expired/depleted computed) and **ADR-060** (recall final, unblock with `lot.unblock`).
- **Question:** May a `recalled` lot be un-recalled? Who can unblock a `blocked` lot? Is `expired` status materialised by a daily job or purely derived from `expiry_date`? What happens to `depleted` lots reached by a customer return (reactivation)?
- **Blocks:** Phase 3. **Related:** BR-LOT-009.

## OD-028 — Applicability of withdrawal-period warnings
- **Question:** Print warnings only on documents for customers of type *breeder*, or on all documents containing products that have withdrawal periods? What when `target_species_id` is not specified on the line (Source view then lists all species)?
- **Blocks:** Phase 10. **Related:** BR-SALE-010.


## OD-029 — Lot cost on repeated receipt of the same lot
- **Question:** A lot is unique per (product, lot number) (ADR-070). If the same lot is received again at a **different unit cost**, what happens to `lots.unit_cost`? Valuation (FR-RPT-002) and margin (FR-RPT-001) depend on it.
- **Options:** (A) weighted-average cost recomputed on each receipt; (B) keep the first cost and ignore the new one; (C) forbid a second receipt of the same lot at a different cost (force a distinct lot identity); (D) track cost per receipt line and value by FIFO layers (needs schema change).
- **Blocks:** Phase 5. **Related:** BR-LOT-002, BR-SALE-009, `products.purchase_price` ("last/avg cost" is ambiguous in the Source).

## OD-030 — Test MySQL container lifecycle
- **Status:** Resolved 2026-10-20 → **ADR-035** (option A).
- **Question:** Testcontainers MySQL is a bean of `TestcontainersConfiguration`, so one container (~10 s start) runs per distinct Spring test-context configuration. Phase 0 has two (plain `@SpringBootTest`, and `RANDOM_PORT` + `RestTestClient`); later phases add more (`@DataJpaTest`, `@MockitoBean` variants). Measured 2026-10-20: warm `./mvnw verify` 45 s, of which ~20 s container start-up; the reported 7 min 46 s run was a cold run (first Maven dependency download and first pull of the 1.12 GB `mysql:8.4` image), not a test-design problem.
- **Options:** (A) keep one container per context (current; strongest isolation, cost grows with each new context); (B) one container per JVM, started once and exposed as `JdbcConnectionDetails` (not as a container bean, so closing one context cannot stop it under another), each context sharing the database — needs a cleanup convention and a dedicated container for "Flyway on an empty DB" migration tests; (C) (A) plus opt-in Testcontainers reuse on developer machines only (`testcontainers.reuse.enable=true` in `~/.testcontainers.properties`, never in CI), which keeps a container alive across runs.
- **Leaning:** `[REC]` keep (A) now; adopt (B) when the integration suite exceeds ~4 contexts, together with the cleanup convention of `testing.md` §3.
- **Blocks:** nothing in Phase 0; decide before Phase 4 (first stock/locking suites). **Related:** ADR-030, `testing.md` §1.7, DR-034.

## OD-031 — Protection against administrator lock-out
- **Status:** Resolved 2026-10-20 → **ADR-042** (option A).
- **Question:** Nothing prevents the last active user holding `user.manage`/`role.manage` from being deactivated (including by themselves), moved to another role, or having those permissions removed from their role. Afterwards nobody can administer users or roles; the only recovery is manual SQL.
- **Options:** (A) server-side guard: an operation that would leave **no active user holding both `user.manage` and `role.manage`** is refused with `INVALID_STATE_TRANSITION` (422), and a user cannot deactivate themselves; (B) no guard, documented recovery procedure (SQL or re-running the bootstrap of ADR-040 on an empty-admin condition); (C) both.
- **Leaning:** `[REC]` (A) — cheap, testable, no new error code.
- **Blocks:** Phase 1 (user and role administration slice). **Related:** ADR-037, ADR-040, BR-SEC-002.

## OD-032 — Display language of seeded reference values
- **Status:** Resolved 2026-10-21 → **ADR-054** (option A, labels confirmed).
- **Question:** ADR-052 stores the seeded species, categories and dosage forms in English, while the UI languages are French (default), Arabic and English (ADR-033). How are they shown, and in which words?
- **Options:** (A) a stable `code` column for the seeded rows only (e.g. `BOVINE`); the UI translates codes through its i18n catalogues and shows administrator-created entries exactly as typed; seeded rows can be neither renamed nor deleted; (B) translated name columns (`name_fr`, `name_ar`) on every reference table, filled by administrators; (C) show the English values as stored.
- **Leaning:** `[REC]` (A), with the French and Arabic labels proposed to the user on 2026-10-21 (awaiting confirmation).
- **Blocks:** Phase 2, reference-data slice (seed migration and screens). **Related:** ADR-033, ADR-052.

## OD-033 — Scan lookup of a code containing "/"
- **Status:** Resolved 2026-10-22 → **ADR-071** (option A).
- **Question:** `GET /api/products/by-barcode/{code}` takes the code in the path. CODE128 and QR codes may contain `/` (ADR-067), and an encoded slash (`%2F`) in a path is rejected by the security firewall, so such a code cannot be looked up this way. `GET /api/products?barcode=` finds it (query string). How should a scan reach every valid code?
- **Options:** (A) move the lookup to a query parameter, `GET /api/products/by-barcode?code=…`, same response; (B) keep the path form and forbid `/` in codes (changes ADR-067); (C) keep the path form and let clients with such codes use the `barcode` search filter.
- **Leaning:** `[REC]` (A): every valid code works and the response is unchanged.
- **Blocks:** Phase 7 (POS scanning). Phase 3 implements the path form as documented. **Related:** ADR-057, ADR-058, ADR-067, BR-UNIT-005.

## OD-034 — Should the product form refuse a max stock at or below the reorder point?
- **Status:** Open (raised 2026-10-23, Phase 4 close).
- **Question:** BR-PROD-005 / BR-STOCK-009 only require `max_stock ≥ min_stock`. A `max_stock` at or below `reorder_point` makes reorder proposals impossible; Phase 4 reports such products as *thresholds inconsistent* (ADR-077 amendment) but still accepts them. Should creating or editing a product refuse it?
- **Options:** (A) refuse it (`VALIDATION_FAILED`, field `maxStock`, new code, plus a DB CHECK through a migration); (B) accept it with a warning on the product form; (C) keep the current behaviour (reported only at generation).
- **Leaning:** none recorded; for the user.
- **Blocks:** nothing in Phase 4; to decide before Phase 5 uses the thresholds for purchase orders. **Related:** BR-PROD-005, BR-STOCK-009, BR-STOCK-010, ADR-077.

---

# Part C — Decision backlog by phase

| Phase | Decisions that must be *Accepted / decided* before it starts |
|---|---|
| 0 Foundation | ~~OD-024~~ → ADR-032, ~~OD-008~~ → ADR-033, ADR-034; ADR-009/010/013/018/019/023/024/025/026/030/031 confirmed Accepted 2026-10-20 |
| 1 Auth | ~~OD-001~~ → ADR-036, ~~OD-023~~ → ADR-037; ADR-017/020/021/027 confirmed Accepted; ADR-038–041, ~~OD-031~~ → ADR-042 (all 2026-10-20) |
| 2 Catalogue | ~~OD-018~~ → ADR-047/048, ~~OD-022~~ → ADR-049/050, OD-025 for SKU → ADR-051, ADR-052, ADR-053, ~~OD-032~~ → ADR-054 (all 2026-10-21) |
| 3 Lots & Units | ~~OD-009~~ → ADR-055/056, ~~OD-019~~ → ADR-057, ~~OD-025~~ → ADR-058, ~~OD-027~~ → ADR-059/060; ADR-061…068 (all 2026-10-22) |
| 4 Inventory & Stock | ~~OD-012~~ → ADR-072…075, ~~OD-013~~ → ADR-076…079, ~~OD-030~~ → ADR-035, ADR-012 (accepted, amended), ADR-028; ADR-080…089 (all 2026-10-22); ADR-090, ADR-091, ADR-077 amendment (2026-10-23); OD-034 open (product form, before Phase 5) |
| 5 Purchasing | OD-007, OD-014, OD-015, OD-017, OD-029 |
| 6 Customers | OD-006, OD-011 |
| 7 Sales / POS | OD-002, OD-003, OD-004, OD-010, OD-006, ~~OD-033~~ → ADR-071 |
| 8 Returns | OD-005 |
| 9 Payments / Finance | OD-003, OD-011, OD-016 |
| 10 Documents & Printing | OD-020, OD-026, OD-028, OD-008 |
| 11 Reports & Audit | OD-021 (backup), OD-002 (margin scope), OD-003 |
| 12 Testing, Security, Deployment | OD-021, ADR-036 hardening review |
| 13 Dark theme | ADR-069 (decided 2026-10-22) |
