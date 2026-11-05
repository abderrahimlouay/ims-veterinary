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
- **Status:** Accepted 2026-10-24 (mechanism); assignment time, format and policy decided by **ADR-097** (OD-017).
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
- **Status:** Accepted (confirmed 2026-10-23 at the Phase 4 close: implemented as written — no business decision reads a view; `v_expiring_lots`, `v_product_stock` and `v_reorder_needed` are not created, their logic lives in services, ADR-056, ADR-076, ADR-080). The remaining reporting views are decided with their phases (Phase 11, DR-006/DR-007).
- **Context:** 8 PostgreSQL views (`v_product_stock`, `v_reorder_needed`, `v_expiring_lots`, `v_sale_withdrawal_warnings`, `v_customer_balance`, `v_supplier_payables`, `v_stock_valuation`, `v_sales_margin`) use `FILTER`, `date_trunc`, `::date`, date arithmetic.
- **Decision:** Re-create them in MySQL syntax (conversion table in `database-migration.md`) **only** where they serve reporting/listing (read-only `@Immutable` entities or projections). Service-level decisions (e.g. credit-limit check, sellable quantity at confirmation) use explicit locking queries, not views.
- **Alternatives:** Drop views and use only repository queries; materialised views (not available in MySQL).
- **Consequences:** Some view definitions have known semantic defects (balance, margin) — see `documentation-review.md` DR-006, DR-007; they must be fixed before being relied upon (**OD-003**).

## ADR-015 — Replacement of PostgreSQL partial indexes
- **Status:** Accepted (confirmed 2026-10-23 at the Phase 4 close: `idx_lots_fefo` and `idx_lots_expiry` exist since V03_003; the generated-column + unique-index technique is also used for one open full count, V04_005, and one pending proposal, V04_008). The `cash_sessions` part applies in Phase 9.
- **Context:** 3 partial indexes: `idx_lots_fefo`, `idx_lots_expiry`, `uq_one_open_session`. MySQL has none.
- **Decision:**
  - `idx_lots_fefo` → ordinary composite index `(product_id, status, expiry_date)`.
  - `idx_lots_expiry` → ordinary index `(expiry_date)` (optionally `(quantity_on_hand, expiry_date)`).
  - `uq_one_open_session` (unique per cashier **only when open**) → a stored/virtual generated column `open_cashier_id = IF(status='open', cashier_id, NULL)` with a `UNIQUE` index (MySQL allows many `NULL`s in a unique index); the rule is **also** checked in `CashSessionService` for a friendly error.
- **Alternatives:** Application-only enforcement (race window); trigger.
- **Consequences:** Extra generated column on `cash_sessions` mapped read-only in JPA.

## ADR-016 — Generated columns retained for line amounts and variances
- **Status:** Accepted (confirmed 2026-10-23 at the Phase 4 close: `inventory_lines.variance_qty` is generated, V04_007; the entity computes the same `counted − balance_at_count` in Java instead of mapping the column, and `InventoryCountsTest` checks that both are equal). The document-line amounts apply from Phase 5 with OD-007.
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
- **Consequences:** Because **MySQL DDL is non-transactional** (implicit commit), each migration must be small, single-purpose and re-runnable; take a backup before production migrations. Applied migrations are never edited. The controlled step is the application's migrate mode (ADR-151, 2026-11-05).

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
- **Status:** Accepted (confirmed 2026-10-23 at the Phase 4 close: package `inventory`, `/api/inventory/...` and `/api/inventory/counts`, tables `inventories`/`inventory_lines` as implemented).
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
- **Consequences:** Strongest isolation; "Flyway on an empty DB" holds by construction. Build time grows ~10 s per new test-context configuration, so tests should reuse the existing configurations (`@SpringBootTest` + `TestcontainersConfiguration`, or the `RANDOM_PORT` variant) rather than add per-class variations. Revisit (new ADR) if the integration suite grows past ~4 configurations or the warm build becomes too slow. `testing.md` §1 (principle 7) and §3.

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
- **Amendment (2026-10-24, ADR-102):** from Phase 5 the suggested quantity subtracts the outstanding quantity of open orders: `max − sellable − outstanding`; ≤ 0 gives no proposal.
- **Amendment (user decision, 2026-10-23, after slice 10):** a product whose `max_stock` is at or below its `reorder_point` is not *thresholds incomplete* but *thresholds inconsistent*: it gets no proposal and is reported apart in the generation result (`thresholdsInconsistent`) and on the reorder page. No validation rule is added to the product form; the form warns about it (OD-034 → ADR-092).

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
- **Amendment (2026-10-24, ADR-103):** an eleventh type, `receipt_cancellation` (−), reverses a cancelled goods receipt; added to the CHECK by a Phase 5 migration (SD-127).

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
- **Amendment (2026-10-24, ADR-098):** the cost is no longer refused: the lot cost becomes the weighted average (or the entry cost when the balance is 0); `COST_MISMATCH` is removed.

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
- **Extension (2026-10-24, Phase 5 slice 1):** `purchasing` uses the same `UserNames` port (who created a price entry, a receipt, a return), with the same architecture rule.

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


## ADR-092 — A max stock at or below the reorder point is warned about, not refused (resolves OD-034)
- **Status:** Accepted (user decision, 2026-10-23, after the Phase 4 close; option B)
- **Context:** BR-PROD-005 / BR-STOCK-009 only require `max_stock ≥ min_stock`. With `max_stock` ≤ `reorder_point` (> 0) no reorder proposal can be generated; the generation reports such products as *thresholds inconsistent* (ADR-077 amendment).
- **Decision:** The product form accepts it and shows a warning on the max-stock field, only when `reorder_point` > 0 and `max_stock` is set and ≤ `reorder_point`. No server validation and no database CHECK. The generation report stays as it is (*thresholds inconsistent*).
- **Alternatives:** refuse it with a field error and a CHECK (A); keep it reported only at generation (C).
- **Consequences:** `frontend.md` §4.1, BR-PROD-005 note, `testing.md` (component test and J8).

## ADR-093 — VAT and rounding per line (resolves OD-007)
- **Status:** Accepted (user decision, 2026-10-24, Phase 5 preflight; #1 A)
- **Decision:** Per line: `line_ht = round(quantity × unit_price × (1 − discount_pct/100), 2)`, `line_vat = round(line_ht × vat_rate/100, 2)`, `line_ttc = line_ht + line_vat`; document totals are the sums of the rounded line amounts. Rounding mode HALF_UP everywhere. The server computes every amount; client values are ignored (BR-SALE-008, BR-PURCH-002). The generated `line_ht` column and the service use the identical formula (ADR-016 parity test).
- **Alternatives:** VAT per rate group on the document (B); round only the document totals (C).
- **Consequences:** BR-PURCH-002, `database.md` purchase_order_lines, `api.md` §4.7. **Before Phase 7** the legal rounding rule for *sales* invoices must be confirmed with an accountant (the user asked for this to be repeated in the Phase 5 close report). **Amended by ADR-115 (2026-10-31):** per line is kept for sales, and the confirmation is now a go-live blocker (DR-040).

## ADR-094 — Purchase-order line prices per ordered unit, 4 decimals
- **Status:** Accepted (user decision, 2026-10-24, Phase 5 preflight; #2 A)
- **Context:** The Source schema had `purchase_order_lines.unit_price DECIMAL(14,2)` per ordered unit, while ADR-049 keeps prices per base unit with 4 decimals and `supplier_price_history` is per base unit.
- **Decision:** A PO line price is per **ordered unit** (the line's unit, base unit when none) with **4 decimals** (`DECIMAL(14,4)`, SD-126). A supplier price-history entry derived from it is stored per base unit: `round(unit_price / factor_to_base, 4)` HALF_UP. Receipt unit costs are per base unit (4 decimals) as on lots.
- **Alternatives:** per ordered unit with 2 decimals (B); always per base unit (C).
- **Consequences:** `database.md` purchase_order_lines, `api.md` §4.7, BR-PURCH-002/008.

## ADR-095 — Non-conforming goods enter stock in a blocked lot (resolves OD-014)
- **Status:** Accepted (user decision, 2026-10-24, Phase 5 preflight; #3 A, BR-LOT-012 (b), plus the user's rules on whole-lot blocking)
- **Decision:** A receipt line marked non-conforming is received like any other (movement `purchase_receipt`, lot found or created) and its lot is **blocked** with the reason "Non-conforming at receipt <number>"; it can then leave through a supplier return (W4 allows blocked lots). When that lot **already has stock**, the whole lot becomes blocked: the screen warns before confirming and shows how many units already in stock will be blocked, and the API refuses the receipt with `LOT_BLOCK_CONFIRMATION_REQUIRED` (context: each such lot, its number and the quantity already in stock) unless the request carries `confirmLotBlocking: true`. A line whose expiry date is already past is refused (`VALIDATION_FAILED`, field `expiryDate`, code `EXPIRED`) **unless** it is marked non-conforming, in which case it follows this rule (BR-LOT-012 (b)). The blocking is audited `LOT_BLOCK` in the same transaction.
- **Alternatives:** refuse non-conforming lines (B); enter them unflagged (C).
- **Consequences:** BR-PURCH-006, BR-LOT-012, W1b, `api.md` §5.4, `frontend.md` §4.5.

## ADR-096 — Over-receipt, free receipts and closing a purchase order (resolves OD-015)
- **Status:** Accepted (user decision, 2026-10-24, Phase 5 preflight; #4 A, A, A, A, A)
- **Decision:** A receipt line linked to a PO line may not exceed the quantity still outstanding (`RECEIPT_QUANTITY_EXCEEDS_ORDER`). A receipt **without PO** (free receipt) is allowed with `receipt.create`. A PO receipt may only contain products of that PO (`VALIDATION_FAILED`, `NOT_ON_ORDER`). Only `sent` and `partially_received` orders can be received (`INVALID_STATE_TRANSITION` otherwise). A partially received order can be **closed with a mandatory reason** (`purchase.validate`); what remains is no longer expected; audited `PO_CLOSE`.
- **Alternatives:** tolerance in % or free over-receipt; no free receipts; extra lines; receiving validated orders; no forced close.
- **Consequences:** BR-PURCH-001/004, W1, `api.md` §4.7–4.8, §5.4.

## ADR-097 — Document numbering policy (resolves OD-017, DR-001)
- **Status:** Accepted (user decision, 2026-10-24, Phase 5 preflight; #5 all recommended); completes ADR-011.
- **Decision:** Format `KEY-YYYY-NNNNNN` (six digits, zero-padded). Keys now: `PO` purchase order, `GR` goods receipt, `SR` supplier return; sales keys are decided in Phase 7. The counter resets each **business-calendar year** (the year of the business date, ADR-033), one `document_sequences` row per `(doc_key, year)`, incremented under row lock inside the creating transaction (ADR-011), so a failed transaction leaves no gap. A purchase order is numbered **at creation** (drafts are kept, never deleted; cancelled orders keep their number); receipts and supplier returns are numbered at confirmation (they are created confirmed). Numbers are never edited or reused (BR-DOC-001).
- **Alternatives:** no yearly reset; PO numbered at validation.
- **Consequences:** BR-DOC-001, `database.md` document_sequences, CT-03 (1,000 parallel allocations: unique, gap-free on commit), `NumberingService` in module `numbering`.

## ADR-098 — Weighted-average lot cost, for receipts and opening stock (resolves OD-029, DR-022)
- **Status:** Accepted (user decision, 2026-10-24, Phase 5 preflight; #6 A, applied to opening stock too, plus the user's rules); amends ADR-084.
- **Decision:** When stock enters an existing lot (receipt or opening stock), under the lot lock and on its **current** balance: `new_cost = round((balance × cost + quantity × entry_cost) / (balance + quantity), 4)` HALF_UP; when the balance is 0 the entry cost **replaces** the old one (no division by zero). Each receipt line keeps its own cost (`goods_receipt_lines.unit_cost`). Opening stock follows the same rule, so `COST_MISMATCH` disappears (ADR-084 amended).
- **Alternatives:** keep the first cost; refuse a different cost; FIFO cost layers.
- **Consequences:** BR-LOT-002, BR-STOCK-014, ADR-084, W1b, W10a, `api.md` §4.5 (opening stock) and §5.4; tests: empty lot, existing lot, two parallel receipts of one lot (CT-08), opening stock with the same rule.

## ADR-099 — `products.purchase_price` is a manual reference price
- **Status:** Accepted (user decision, 2026-10-24, Phase 5 preflight; #7 A)
- **Decision:** `products.purchase_price` stays a manual reference price (per base unit, `price.edit`, audited `PRICE_CHANGE`, BR-PROD-006); receipts never change it. It prefills opening stock, and a PO line when the supplier has no price-history entry for the product; otherwise the latest entry of that supplier prefills the line (converted to the ordered unit).
- **Alternatives:** last receipt cost; computed average.
- **Consequences:** `database.md` products, `frontend.md` §4.5.

## ADR-100 — Suppliers and their price history
- **Status:** Accepted (user decision, 2026-10-24, Phase 5 preflight; #8 all recommended)
- **Decision:** Kinds `laboratory` / `wholesaler`; tax id optional free text; **name unique ignoring case and accents** (column collation, `DUPLICATE_RESOURCE`, SD-129); payment terms in days ≥ 0. A supplier may be deactivated at any time, also with open orders, whose receipts are then refused (`SUPPLIER_INACTIVE`, BR-PURCH-011); reactivation allowed; never deleted. A price-history entry (per base unit, discount 0–100, `valid_from` = business date) is appended **on receipt confirmation** only when the price or discount differs from the latest entry for that supplier and product, and manual entries are allowed with `supplier.manage` (BR-PURCH-008). A receipt line's discount is that of its PO line (0 for a free receipt).
- **Consequences:** BR-PURCH-008/011, `api.md` §4.6, `database.md` suppliers.

## ADR-101 — Supplier returns in Phase 5: stock only
- **Status:** Accepted (user decision, 2026-10-24, Phase 5 preflight; #9 A / B / yes)
- **Decision:** A supplier return has a number (`SR`), a supplier, a mandatory reason and lines (lot, quantity ≤ lot balance); it posts `supplier_return` (−) movements through `StockService` and is audited `RETURN_CREATE`, all in one transaction. Only lots whose `supplier_id` is that supplier, or empty (opening stock, found lots), can be returned to it (`VALIDATION_FAILED`, `OTHER_SUPPLIER`). Active, blocked, recalled and expired lots may be returned. The financial side (supplier credit note) stays Phase 9 (OD-016, DR-009).
- **Consequences:** BR-PURCH-009, W4, `api.md` §4.8a.

## ADR-102 — Converting reorder proposals to purchase orders; open orders subtracted
- **Status:** Accepted (user decision, 2026-10-24, Phase 5 preflight; #10 A, A, A); completes ADR-079, amends ADR-077.
- **Decision:** The user selects pending proposals and picks the supplier at conversion (prefilled with the supplier of the latest price-history entry for the product); one **new draft PO per supplier** is created with one line per proposal (base unit, suggested quantity, price prefilled per ADR-099); each proposal becomes `converted` with its `supplier_id` (SD-121) and converts once (BR-PURCH-010). From Phase 5 the generation subtracts open orders: `suggested = max_stock − sellable − outstanding`, where *outstanding* is the ordered but not yet received base quantity on `validated`, `sent` and `partially_received` orders (drafts not counted); when that is ≤ 0 no proposal is created (an existing pending proposal is left as is, ADR-079).
- **Consequences:** BR-STOCK-010, BR-PURCH-010, W10, `api.md` §4.5.

## ADR-103 — Cancelling a whole goods receipt
- **Status:** Accepted (user decision, 2026-10-24, Phase 5 preflight; #11 B with the user's rules)
- **Decision:** A confirmed goods receipt can be cancelled **as a whole**, with a mandatory reason and the new permission `receipt.cancel` (Administrator, Pharmacist), only if **none of its lots has any movement after the receipt's own movement** on that lot; otherwise `RECEIPT_NOT_CANCELLABLE` names the lot and the later movement (type, date). In one transaction: one reversal movement of the new type `receipt_cancellation` (−, SD-127) per receipt line through `StockService`, the PO lines' received quantities and the PO status rolled back, the receipt marked `cancelled` (it keeps its number, BR-DOC-001, and is shown as cancelled), audited `RECEIPT_CANCEL`. A lot created by the receipt stays, with a zero balance (lots are never deleted). OD-004 (sales) and OD-005 (returns) are unaffected.
- **Consequences:** new BR-PURCH-012, BR-STOCK-005 (sign of the new type), ADR-081 (eleven types), `security.md` §4 and §7, W1d, `api.md` §4.8, permission seed and matrix test.

## ADR-104 — Idempotency keys for confirmed documents
- **Status:** Accepted (user decision, 2026-10-24, Phase 5 preflight; #12a A)
- **Decision:** `POST` of numbered documents (from Phase 5: goods receipts; later sales and payments) accepts `Idempotency-Key` (UUID). A table `idempotency_keys` (SD-128) stores per user and endpoint the key, a hash of the request body and the created resource, written in the same transaction as the document: the same key with the same body returns the first result (no second number, movement or document); with another body `IDEMPOTENCY_CONFLICT`. Keys are kept (no purge until a retention is decided).
- **Consequences:** `api.md` §1, §5.4; BR-PURCH-007; reused by BR-SALE-016 in Phase 7.

## ADR-105 — Receipt and purchase-order details
- **Status:** Accepted (user decision, 2026-10-24, Phase 5 preflight; #12b–12h A, scope confirmed)
- **Decision:** (b) A receipt into an existing **recalled** lot is refused (`LOT_RECALLED`); a **blocked** lot is accepted and stays blocked. (c) `lots.supplier_id` is set by the first receipt and kept (later receipts from another supplier do not change it; opening-stock and found lots have none). (d) A PO line's VAT rate is prefilled from the product and editable while draft. (e) The same product twice in one PO is refused (`VALIDATION_FAILED`, `DUPLICATE`). (f) PO prices and receipt costs are typed with `purchase.create` / `receipt.create`; `price.edit` stays for catalogue prices. (g) Audited: PO cancel (`PO_CANCEL`), forced close (`PO_CLOSE`), supplier returns (`RETURN_CREATE`), receipt cancellation (`RECEIPT_CANCEL`); receipts and other PO changes are traced by the documents and the ledger. (h) Supplier invoices, payments and payables stay in Phase 9; the PO PDF in Phase 10.
- **Consequences:** `security.md` §7, `api.md` §4.6–4.8, W1.

## ADR-106 — What a receipt cancellation restores
- **Status:** Accepted (user decision, 2026-10-24, Phase 5 plan; Q1 A, Q2 A with a condition, Q3 A, Q4 variant, Q5 B); completes ADR-103.
- **Decision:** Cancelling a receipt (ADR-103) also undoes, per line, what the receipt changed outside the ledger:
  1. **Lot cost:** each receipt line stores the lot's cost before the receipt (SD-131); the cancellation restores it. A lot created by the receipt has no previous cost and keeps its cost (its balance is 0).
  2. **Lot status:** each receipt line stores the lot's status before the receipt and whether the receipt blocked the lot (SD-131). The previous status is restored **only if the lot is still in the state this receipt set**: status `blocked` with the block date and reason written by this receipt (status changes are not movements, so the lot's status data are compared, not the ledger). If the status changed after the receipt (unblocked, blocked again, recalled), it is left as it is. A restore is audited `LOT_UNBLOCK`.
  3. **Purchase order:** an order closed by full reception goes back to `sent` or `partially_received` from the remaining received quantities; an order closed by a forced close stays closed.
  4. **Price history:** an entry appended by the receipt is kept but marked cancelled by that receipt (SD-132); it is shown struck through and is ignored by the PO price prefill, by "latest price" and by the comparison that decides whether a later receipt appends an entry. Nothing is deleted.
  5. **Lot supplier:** if the receipt created the lot, `lots.supplier_id` is cleared; a later receipt sets it again (the first supplier is kept, ADR-105).
- **Consequences:** BR-PURCH-008, BR-PURCH-012, W1d, `database.md` goods_receipt_lines and supplier_price_history, SD-131, SD-132, `api.md` §4.6/§4.8, `frontend.md` §4.5; tests: cost restored, status restored, and block/unblock/recall between the receipt and its cancellation leave the status untouched; cancelled history entry ignored by the prefill.
---

## ADR-107 — Price precedence on a sales line (resolves OD-006)
- **Status:** Accepted (user decision, 2026-10-24, Phase 5 close; option A).
- **Decision:** The **most specific price wins; prices never stack.** The unit price of a sales line is, in this order, the first that exists: (1) a manual line price (permission `price.edit`, audited `PRICE_CHANGE`); (2) the customer's own price for the product (`customer_product_prices`, per base unit × the unit factor); (3) the unit's sale price (`product_units.sale_price`); (4) the product price per base unit × the unit factor (ADR-049, ADR-064). The **customer-category discount** (`customer_categories.discount_pct`) applies only when the customer has no own price for the product. A **manual discount** is added on top, up to the user's entitlement; beyond it needs `sale.discount` (BR-SALE-007, audited `DISCOUNT_OVERRIDE`).
- **Alternatives:** stack base price, category discount and manual discount multiplicatively (B); the lowest applicable price wins (C).
- **Consequences:** BR-SALE-006, BR-SALE-007, `domain-model.md` PriceResolver, `database.md` customer_product_prices, W2 step 3, testing.md (BR-SALE-006 unit tests of each level and of "no stacking"); customer prices and categories are stored in Phase 6, the resolution runs from Phase 7.

## ADR-108 — Credit-limit semantics (resolves OD-011, DR-025)
- **Status:** Accepted (user decision, 2026-10-24, Phase 5 close; option C).
- **Decision:** `customers.credit_limit` becomes **nullable**: `NULL` = no limit, `0` = cash only (no credit sale), a positive amount = the limit (SD-133). A credit sale is allowed only if **balance + the new sale's TTC ≤ limit**, where the balance is the customer's confirmed invoices minus payments (drafts not counted), evaluated **under the customer-row lock** (database.md §7 lock order: after the lots). Overdue invoices block a new credit sale **only when** the new setting `credit_overdue_block_days` is on (default off; when on, an invoice overdue by more than that many days blocks). **Phase 6** stores the limit (`customer.credit.manage`) and audits its changes (`CREDIT_LIMIT_CHANGE`); the **check lands with sales** (Phase 7, through `CreditLimitPort`), and Phase 9 brings payments into the balance.
- **Alternatives:** 0 = no credit, overdue always blocks (A); 0 = unlimited, overdue only warns (B).
- **Consequences:** BR-CUST-002, BR-PAY-005, CT-06, `database.md` customers, `api.md` §4.9 credit-limit endpoint (`null` allowed), settings seed (Phase 6 or 7 migration), W2 step 6. **Open point for Phase 7 closed by ADR-116** (credit sales are enabled from Phase 7). ADR-117 adds uninvoiced credit delivery notes to the check, not to the balance.

## ADR-109 — The customer record
- **Status:** Accepted (user decision, 2026-10-25, Phase 6 preflight; D1 B, D2 B, D7 A, D8 A, D11 A, D12).
- **Decision:** Besides the documented fields, a customer has an optional `tax_id` (free text, like suppliers, ADR-100), an optional `email` and `payment_terms_days ≥ 0` (default 0; prefills an invoice's due date from Phase 7) (SD-134). A new customer's credit limit is **0 (cash only)**: granting credit is a separate, audited act (`customer.credit.manage`, ADR-108). `veterinarian_id` references an **active** customer of type `veterinarian`, never the customer itself; changing the type of a veterinarian who is referenced is refused (`RESOURCE_IN_USE`); deactivating a referenced veterinarian is allowed and the links stay. Wilaya, commune and farming type are free text (searched ignoring case and accents); reference lists may come with zone reports (Phase 11). Customers are **never deleted**: there is no delete endpoint (`DELETE` answers 405), only deactivation. Changing the credit limit locks the customer row (`SELECT … FOR UPDATE`), the lock Phase 7's credit check takes (database.md §7 order unchanged).
- **Alternatives:** no extra fields; structured NIF / NIS / RC / AI (to revisit with Phase 10 invoice legal mentions, DR-021); a new customer without limit; reference lists for the location now.
- **Consequences:** BR-CUST-001…003, SD-134, `database.md` customers, `api.md` §4.9.

## ADR-110 — Duplicate customers
- **Status:** Accepted (user decision, 2026-10-25, Phase 6 preflight; D3 B, additions 1 and 2).
- **Decision:** Refused with `DUPLICATE_RESOURCE`: the same name (ignoring case and accents) **and** the same phone after normalisation, or the same tax id after normalisation. Only warned: the same name alone or the same phone alone, through `GET /api/customers/similar` (`customer.read`), which the form calls before saving. **Normalisation:** phone — spaces, dots, dashes and parentheses removed, `+213`, `00213` and a leading `0` equivalent (stored as a key of the national number); tax id — trimmed, upper-cased, spaces removed; a missing phone or tax id never conflicts. The database enforces both refusals with unique keys (name + phone key; normalised tax id), so parallel creations cannot both pass. The refusal and the similar-customers answer carry **ids and the matching field only** (`context: {field, customerId}`), never another customer's data, in a response, an error message or a log line. Each normalisation is unit-tested.
- **Alternatives:** no detection; refuse the same name.
- **Consequences:** BR-CUST-005, SD-134, `api.md` §4.9.

## ADR-111 — Customer prices
- **Status:** Accepted (user decision, 2026-10-25, Phase 6 preflight; D4 A, D5 B, D13 B).
- **Decision:** One price per customer × product, **per base unit** (ADR-049); the price for a unit is that price × the unit factor (ADR-107); no validity dates: the history is the `PRICE_CHANGE` audit (old and new value, same transaction, BR-PROD-006). Managed one at a time with `customer.price.manage`: `PUT /api/customers/{id}/prices/{productId}` (set or change) and `DELETE …/{productId}` (remove), each audited; a price for an archived product is refused (`PRODUCT_ARCHIVED`), existing ones are kept. The foreign keys are `RESTRICT` (customers and products are never deleted, SD-134).
- **Alternatives:** a price per unit; validity periods without overlap; replacing the whole list at once.
- **Consequences:** BR-CUST-006, `database.md` customer_product_prices, `api.md` §4.9.

## ADR-112 — Customer categories
- **Status:** Accepted (user decision, 2026-10-25, Phase 6 preflight; D10 A, addition 5).
- **Decision:** Category names are unique ignoring case and accents; a category can be renamed but not deleted (no endpoint). A change of its discount is audited (`PRICE_CHANGE` on `customer_categories`, old and new value) and **never rewrites existing documents**: from Phase 7 a sales line stores the price and discount it applied.
- **Alternatives:** no audit of the discount.
- **Consequences:** BR-CUST-007, `api.md` §4.9.

## ADR-113 — Discount entitlement on a sales line (clarifies ADR-107)
- **Status:** Accepted (user decision, 2026-10-25, Phase 6 preflight; D6 A, consequence confirmed).
- **Decision:** The line discount starts at the customer's category discount (only when the customer has no own price for the product, ADR-107). Any discount up to that **entitlement** is allowed; above it needs `sale.discount` (BR-SALE-007). Manual and category discounts are **not added**. Consequence: on a product with the customer's own price the entitlement is 0, so any manual discount there needs `sale.discount`.
- **Alternatives:** additive category + manual discount.
- **Consequences:** BR-SALE-006/007, the `PriceResolver` precedence table (Phase 6), Phase 7 lines.

## ADR-114 — Customer personal data in logs, events, audit and fixtures
- **Status:** Accepted (user decision, 2026-10-25, Phase 6 preflight; D9 A, additions 3 and 4).
- **Decision:** A customer's **name, phone, email, address and tax id** are never written to application logs, security events or audit entries; those carry ids, amounts and codes only. Customer creation and edits are therefore not audited (the audit log is append-only and could never be purged); credit-limit and price changes are, with ids and amounts. Tests and demo data use fictional names and phone numbers from a reserved fake range only. A test creates and edits a customer and asserts that the captured log output contains none of those values. Retention and erasure stay with OD-021.
- **Alternatives:** audit every customer change (`CUSTOMER_UPDATE`).
- **Consequences:** `security.md` §9, `testing.md`, the demo seeder (Phase 6 slice 7). The reserved fake range is `0100 00 xx xx` (testing.md §3, DR-039).

## ADR-115 — Sales invoices round per line; legal confirmation pending (confirms ADR-093 for sales)
- **Status:** Accepted (user decision, 2026-10-31, Phase 7 preflight; D1 A).
- **Decision:** Sales documents use the formula of ADR-093: VAT and rounding per line, HALF_UP, document totals are the sums of the rounded line amounts. VAT per rate group is not used.
- **Alternatives:** VAT per rate on the document total (B); two lines of 0.33 HT at 19 % give 0.12 VAT per line and 0.13 per rate.
- **Consequences:** BR-SALE-008, `api.md` §5.5/§5.6, `testing.md` BR-SALE-008. The legal rule has **not** been confirmed: an accountant must confirm it before go-live. This is a **go-live blocker, not a Phase 7 blocker** (DR-040). If the accountant requires another rule, it is changed by a new ADR before any real invoice exists.

## ADR-116 — Credit sales are enabled from Phase 7 (closes the open point of ADR-108)
- **Status:** Accepted (user decision, 2026-10-31, Phase 7 preflight; D2 A).
- **Decision:** Credit sales work from Phase 7 with the limit enforced server-side (BR-PAY-005). Payments only enter the balance in Phase 9, so until then a customer's balance only grows. Go-live is not before Phase 9 (the roadmap deploys in Phase 12), so no real user is affected.
- **Alternatives:** disable credit sales until Phase 9 (B); bring customer payments forward into Phase 7 (C).
- **Consequences:** **Demo and test customers will eventually hit their limit; raising the limit (`customer.credit.manage`, audited) is the workaround.** CT-06 runs with real sales from Phase 7. `finance` implements `CreditLimitPort` from Phase 7, with no payment side until Phase 9. A go-live before Phase 9 would reopen this decision.

## ADR-117 — Delivery notes, invoices and receipts move stock, once (resolves OD-002, DR-004)
- **Status:** Accepted (user decision, 2026-10-31, Phase 7 preflight; D3 B and its follow-up).
- **Decision:** Confirming a **delivery note**, a **receipt** or an **invoice not derived from a delivery note** posts `sale` (−) movements. A quote never does (BR-SALE-003). An **invoice made from a confirmed delivery note reuses its lots and posts no second movement**. The recall buyers list covers every confirmed document that posted movements, including anonymous receipts and delivery notes not yet invoiced. **Uninvoiced credit delivery notes count in the credit-limit check but not in the balance:** the check is `balance + uninvoiced credit delivery notes + the new document's TTC ≤ limit`.
- **Alternatives:** only invoices and receipts move stock, the delivery note is informational (A): delivered goods would stay in stock on paper and a recall would miss them.
- **Consequences:** BR-SALE-002, BR-PAY-005, BR-LOT-008, W2, W6, `database.md`, ADR-108 (the check is widened; the balance is unchanged), `v_sales_margin` scope (DR-007, Phase 11). The commercial rules of a delivery note: ADR-125.

## ADR-118 — Every immediate sale creates a payment; balance definition (resolves OD-003, DR-003, DR-005)
- **Status:** Accepted (user decision, 2026-10-31, Phase 7 preflight; D4 A, DR-003 option a).
- **Decision:**
  1. Every immediate sale creates `customer_payments` rows and allocations to the document at confirmation, written by `finance` through a port defined in `sales`, in the confirmation transaction. `customer_payments.customer_id` becomes **nullable** for anonymous sales; there is no fake customer (SD-108).
  2. An immediate sale is **paid in full**: Σ payments = TTC, several methods allowed. Change given on cash is not recorded.
  3. A cash session is required **only when the payment includes cash** (`CASH_SESSION_REQUIRED`); the document is then linked to that session and a `cash_movements` IN row is written for the cash part. Cheques and transfers need no session and stay out of expected cash (BR-CASH-003).
  4. A credit sale takes **no payment at confirmation**.
  5. **Balance** (derived, never stored) = confirmed credit invoices − their credit notes − account payments, allocated or not. Counter payments of immediate sales never count. Anything received on account beyond the invoices lowers the balance as an advance; its handling on screen is Phase 9.
  6. As ADR-017 provided once this was confirmed, `customer_payments`, `payment_allocations` and `cash_movements` get the append-only triggers (SD-102); errors are reversed (BR-PAY-003).
  7. **Amount kept (user check, 2026-10-31):** the payment, the cash movement and the session's expected amount use the amount kept, which is the sale total, never the amount tendered. The API takes no tendered amount; the screen computes the change. A test covers a cash sale with change.
  8. **DR-003 option (a):** Phase 7 delivers a minimal slice: opening and closing a session (with variance) and the cash movements of sales. Manual cash movements and the session report stay in Phase 9.
- **Alternatives:** payment method stored on the document, `customer_payments` only for account settlements (B): no split payment and two payment paths.
- **Consequences:** BR-SALE-013, BR-PAY-004, BR-PAY-006, W2, W9, `api.md` §5.6 and the cash-session endpoints, `database.md`, DR-006 (the balance query follows this definition in Phase 9), `database-migration.md` SD-102/SD-108. Who may take cash at the counter: ADR-126.

## ADR-119 — Restricted cancellation of confirmed sales documents (resolves OD-004, DR-029)
- **Status:** Accepted (user decision, 2026-10-31, Phase 7 preflight; D5 C).
- **Decision:** A confirmed document is cancelled **as a whole**, following the pattern of ADR-103, with permission `sale.cancel` and a mandatory reason. It is **refused** (`SALE_NOT_CANCELLABLE`, 409, `context` names the blocking item) if a return, a credit note or a derived document exists, or if its cash session is closed; those cases go through a credit note (Phase 8). In one transaction: one `sale_cancellation` (+) movement per posted line through `StockService` (new movement type, SD-135), recorded payments reversed and the cash part posted OUT on the same session, status `cancelled`, `cancelled_by/at` and the reason stored, audited `INVOICE_CANCEL`. The **number is kept** (BR-DOC-001). The stock returns to the original lot whatever its status, and a blocked, recalled or expired lot stays unsellable. A cancelled document no longer counts in the balance or in the credit-limit check.
- **Amendment (user decision, 2026-11-03, Phase 9 slice 1):** a **credit invoice** that a payment on account settles (an allocation of a payment still in force) is **refused** (`SALE_NOT_CANCELLABLE`, `blockedBy: payment`, `paymentId`): the payment is reversed first (ADR-135), or a credit note is made by a return. "Recorded payments reversed" above concerns the counter payments of immediate sales only; a payment on account is never reversed by a cancellation.
- **Alternatives:** cancellation with `sale.cancel` and no conditions (A); credit note only (B).
- **Consequences:** BR-SALE-004, BR-SALE-014, BR-STOCK-005, W2b, `api.md` (`POST /api/sales/{id}/cancel`, new code), `database.md` (`cancel_reason`, movement CHECK), `testing.md` BR-SALE-014, DR-002 (the discard type stays with OD-005).

## ADR-120 — FEFO override needs a permission and a reason (resolves OD-010)
- **Status:** Accepted (user decision, 2026-10-31, Phase 7 preflight; D6 A).
- **Decision:** A seller may choose another lot than the FEFO proposal only with `sale.override_fefo` (Administrator, Pharmacist) and a **mandatory reason**, audited `FEFO_OVERRIDE` in the same transaction; without the permission the result is `FEFO_VIOLATION`. Expired, blocked and recalled lots can **never** be selected, with or without the permission (`LOT_EXPIRED` / `LOT_BLOCKED` / `LOT_RECALLED`, BR-LOT-003/004).
- **Alternatives:** never allowed (B); allowed for any seller with a warning only (C).
- **Consequences:** BR-LOT-005, `api.md` §5.5 (`lotId` + `fefoOverrideReason`), `frontend.md` §POS, `security.md`.

## ADR-121 — On-screen withdrawal warnings (resolves OD-028 for the screen only)
- **Status:** Accepted (user decision, 2026-10-31, Phase 7 preflight; D7 A).
- **Decision:** The sale screen shows a non-blocking `WITHDRAWAL_PERIOD` warning for **any customer**, anonymous included, on a line whose product has withdrawal periods: for the line's target species, or **every species of the product when none is given**. A product without withdrawal data shows nothing. **The printing rule stays open: OD-028, Phase 10.**
- **Alternatives:** breeders only (B): an anonymous buyer would never see the warning.
- **Consequences:** BR-SALE-010, W2 step 3, `api.md` §5.5, `testing.md` BR-SALE-010.

## ADR-122 — Sales document numbering (resolves OD-035; completes ADR-097)
- **Status:** Accepted (user decision, 2026-10-31, Phase 7 preflight; D8 A).
- **Decision:** Keys `QUO` quote, `DN` delivery note, `INV` invoice, `RCT` receipt, `CN` credit note, format and yearly reset per ADR-097. A sales document is numbered **at confirmation**, in the confirming transaction, so an abandoned draft uses no number and a failed confirmation leaves no gap. `sales_documents.number` is **nullable while the document is a draft** (`UQ(doc_type, number)` ignores NULLs; CHECKs require a number on a confirmed document and none on a draft, SD-136). A draft that is abandoned stays cancelled without a number; a cancelled confirmed document keeps its own. `CN` is issued from Phase 8.
- **Alternatives:** numbered at creation, as purchase orders (B): abandoned drafts leave gaps.
- **Consequences:** BR-DOC-001, W2 step 6, `database.md` sales_documents, `api.md` §4 (a draft has `number: null`).

## ADR-123 — Sales line prices per sold unit, 4 decimals (resolves OD-036)
- **Status:** Accepted (user decision, 2026-10-31, Phase 7 preflight; D9 A).
- **Decision:** `sales_document_lines.unit_price` is `DECIMAL(14,4)`, per **sold unit** (the line's unit, base unit when none), as purchase-order lines (ADR-094, SD-137). Line amounts and totals stay 2 decimals (ADR-115).
- **Alternatives:** keep 2 decimals (B): a price like 0.1234 per ml would be rounded before the line total is computed.
- **Consequences:** `database.md` sales_document_lines, `api.md` §5.5, ADR-016 parity test, ADR-107.

## ADR-124 — Phase 7 has no prescription flag; the client's answer is a go-live blocker (resolves OD-037)
- **Status:** Accepted (user decision, 2026-10-31, Phase 7 preflight; option A).
- **Decision:** Phase 7 is built without any prescription attribute or check. Whether some products may be sold only on a veterinary prescription remains a question to the client, and its answer is a **go-live blocker** (DR-041). If the client requires it, it is added by a new ADR and a migration; confirmed documents are not changed.
- **Alternatives:** a product flag that refuses the sale without a prescription reference (B); a product flag with a warning only (C).
- **Consequences:** `documentation-review.md` DR-041, `roadmap.md`.

## ADR-125 — Commercial rules of a delivery note (resolves OD-038)
- **Status:** Accepted (user decision, 2026-10-31, Phase 7 preflight; option A).
- **Decision:** A delivery note takes **no payment**. One whole delivery note gives **exactly one invoice**, with lines, lots and prices frozen; there is no partial invoicing and no grouping. The invoice inherits the credit status: a non-credit invoice is paid in full at its confirmation (ADR-118), a credit invoice takes no payment. Confirming that invoice makes **no new limit check**, since the amount is already counted and the goods have left. Cancelling it (ADR-119) posts no stock movement and makes the delivery note invoiceable again; a delivery note with a non-cancelled invoice cannot be cancelled.
- **Alternatives:** partial invoicing, tracked per line (B); several delivery notes on one invoice, which needs a link table (C).
- **Consequences:** BR-SALE-001, BR-SALE-002, BR-PAY-005, W2, `api.md` convert endpoint, `testing.md` BR-SALE-002.

## ADR-126 — The Pharmacist may open a cash session (resolves OD-039; amends ADR-037)
- **Status:** Accepted (user decision, 2026-10-31, Phase 7 preflight; option A).
- **Decision:** The Pharmacist role receives `cash.open`, by a migration in the cash-session slice, so that the role can take cash sales from its own session. `cash.manage` is unchanged.
- **Alternatives:** keep the matrix, so Pharmacists make only credit, cheque and transfer sales (B).
- **Consequences:** `security.md` §4 changes **with that migration**, because `PermissionMatrixSeedTest` requires the matrix to equal the seeded grants.

## ADR-127 — Discarded returned goods enter the ledger and leave it at once (resolves OD-005 question 1, SD-107, DR-002)
- **Status:** Accepted (user decision, 2026-11-02, Phase 8 preflight; Q1 option C, Q11).
- **Decision:** A returned line with disposition `discard` posts, through `StockService`, **two movements on the original lot in the return's transaction**: `customer_return` (+) then the new type **`return_discard` (−)** for the same quantity. Both reference the return line (`reference_type = 'customer_return_line'`, its id), so the lot's balance is unchanged and the ledger shows the goods coming back and being destroyed. The pair is allowed whatever the lot's status (expired, blocked, recalled), because nothing remains in the lot. A discard carries a **mandatory reason code**: `opened`, `packaging_damaged`, `expired`, `recalled`, `cold_chain_doubt`, `other`; `other` requires an explanatory text (≤ 500 characters). The code and the text are stored on the return line, copied into the `return_discard` movement's `reason` and written in the `RETURN_CREATE` audit entry. The discarded quantity is a loss valued at the lot's unit cost, reported apart from expiry and breakage adjustments (Phase 11).
- **Alternatives:** no movement (A): the loss disappears from the books and the goods waiting for destruction are invisible; `customer_return` + `adjustment_expiry` (B): wrong heading for opened or damaged goods, and DR-002 forbids reusing `adjustment_*`; a quarantine quantity destroyed later with a certificate (D): needs a quarantine concept that does not exist, can be built on top of this ADR later without migrating data.
- **Consequences:** BR-STOCK-005, BR-RET-003, BR-RET-010, SD-107 (accepted), `database.md` movement CHECK, `workflows.md` W3, `testing.md` BR-RET-003/010. DR-002 resolved.

## ADR-128 — Settlement of a customer return: refunded in full, or credited to the account (resolves OD-005 question 2)
- **Status:** Accepted (user decision, 2026-11-02, Phase 8 preflight; Q2, Q3).
- **Decision:**
  1. A return on a **credit sale** issues its credit note and **no refund**: the credit note lowers the customer's balance (ADR-118 item 5). A balance below zero is an advance, handled in Phase 9.
  2. A return on an **immediate sale** (a receipt, or an invoice not bought on credit) is **refunded in full in the return's transaction**: Σ refund tenders = the credit note's TTC exactly, several methods allowed, otherwise `REFUND_TOTAL_MISMATCH`. A refund smaller than its credit note is never recorded. A return on a credit sale with tenders gets the same code.
  3. Methods: `cash`, `cheque`, `bank_transfer` (BR-PAY-001); **no card** (a card method needs a new ADR for payments and refunds together). The method need not be the original payment's; the screen proposes the original one and the audit records both. Cheque and transfer need a reference.
  4. A **cash** refund needs the refunder's **own open cash session** (`CASH_SESSION_REQUIRED`), whatever session the original sale used (BR-CASH-004), and is posted as a `cash_movements` OUT row on it under the session-row lock. It is **refused** with `CASH_INSUFFICIENT` if it would bring the session's expected cash (BR-CASH-003) below zero.
  5. Refunds are rows of a new append-only table **`customer_refunds`** (finance module, written through a port defined in `sales`, triggers as SD-102), linked to the credit note; `cash_movements.customer_refund_id` names the refund of a cash movement (SD-139). They are not negative payments and not payment reversals (a reversal cancels a whole payment, SD-138).
  6. A refund exists only inside its return: there is no refund endpoint in Phase 8. The return's `Idempotency-Key` (ADR-104) makes a replay return the first result, so a refund cannot be paid twice.
  7. **Store credit** (keeping the amount on the account of an identified customer who bought for cash) is deferred to Phase 9 with advances (OD-016). **Phase 9 (ADR-137): stays out** until the client asks for it.
  8. Each refund is audited `REFUND_CREATE` (credit note, method, amount, original document and its payment methods, session) in the same transaction.
- **Alternatives:** a refund made later through its own endpoint (B): an open liability to track and more ways to refund twice; store credit now (C): depends on the Phase 9 advances.
- **Consequences:** BR-RET-006, BR-RET-011, BR-CASH-003 (refunds are cash OUT), `api.md` §5.8, `database.md` §3.8, `security.md` §7, `testing.md`. The expected cash of a session includes refunds automatically.

## ADR-129 — Which returned goods go back to their lot (resolves OD-005 question 3; amends BR-RET-002)
- **Status:** Accepted (user decision, 2026-11-02, Phase 8 preflight; Q4 with conditions, Q6, Q8).
- **Decision:**
  1. Disposition `restock` puts the goods **back into their original lot** with a `customer_return` (+) movement. It is allowed only if the goods are **sealed** (stated per line by the user), the lot is **not expired** on the return's business date (ADR-055), and the product allows it (item 3). Otherwise the request is refused with `RETURN_NOT_RESTOCKABLE` (`context.reason`: `not_sealed`, `lot_expired`, `product_restricted`): the server **never switches a line to discard by itself**. Expired goods are always discarded (FR-SALE-006).
  2. Sealed goods of a **blocked or recalled** lot may be restocked into that lot. The return **never changes the lot's status**: the units stay unsellable (BR-LOT-004) and leave only through an authorised workflow — a supplier return (W4, `return.supplier.create`), a stock adjustment such as destruction (W7, `stock.adjust`) or an inventory regularisation. A blocked lot becomes sellable again only by the existing audited unblock (`lot.unblock`, BR-LOT-009); a recalled lot never does.
  3. A product flag **`return_restock_forbidden`** (default `false`) keeps returned units of that product out of sellable stock: restocking it into an **active or blocked** lot is refused (`product_restricted`); returning sealed units to a **recalled** lot stays allowed, since that lot is never sellable again and the manufacturer may take them back. The flag is changed with `product.update` and audited `PRODUCT_RETURN_POLICY_CHANGE` (old and new value) in the same transaction (SD-142).
  4. The server computes each line's eligibility for the screen (preview, `api.md` §5.8); the screen pre-selects `discard` and disables `restock` where it is not allowed. The UI is a convenience; the check is the server's (BR-SEC-002).
  5. A **confirmed return is immutable**: its dispositions never change. Restocked units later found unusable are ordinary stock and are corrected by the existing adjustment workflow (W7) with a reason that may quote the return number; discarded units cannot come back.
- **Alternatives:** recalled and blocked goods always discarded (BR-RET-002 as written): the manufacturer could not take recalled units back through W4; reclassifying a disposition after confirmation: more states for the same effect as W7; silently forcing `discard`: the user's request is changed without notice.
- **Consequences:** BR-RET-002 (amended), BR-PROD-009, BR-LOT-004 (unchanged, now cited by returns), `database.md` products, `security.md` §7, `frontend.md` return wizard, `testing.md`.

## ADR-130 — The returned quantity can never exceed the sold quantity; credit-note amounts (Phase 8)
- **Status:** Accepted (user decision, 2026-11-02, Phase 8 preflight; Q9 in principle; the rest technical, approved with the report).
- **Decision:**
  1. **Invariant:** for every sale line, Σ returned `quantity_base` ≤ the line's `quantity_base` (BR-RET-001). The service checks it under the **original document's row lock** (`SELECT … FOR UPDATE`, the lock cancellation takes, ADR-119), so two returns of one document, or a return and a cancellation, run one after the other and the second re-reads what the first committed. The database backs it up: `sales_document_lines.returned_quantity_base` (default 0), raised in the return's transaction, with `CHECK (returned_quantity_base <= quantity_base)` (SD-140), like `lots.quantity_on_hand` for BR-STOCK-001. The integrity check compares it with Σ return lines.
  2. **Lock order** (database.md §7): `idempotency_keys` → original `sales_documents` row → `document_sequences` (return `CR`, credit note `CN`) → lots by ascending id (`StockService`) → cash session row (cash refund). No new cycle.
  3. The returned quantity is entered in the line's sold unit or in the product's base unit and stored in base units (ADR-049).
  4. **Credit-note line amounts.** A credit-note line copies the original line's product, lot, unit, unit price, discount and VAT rate (BR-SALE-008: prices are never re-resolved). Its HT = `ROUND(original quantity × unit price × (1 − discount) × returned base ÷ sold base, 2)` (the pro-rata of the line's unrounded amount, HALF_UP) and its VAT = `ROUND(HT × rate, 2)` per line (ADR-115); a partial credit never takes the line's credited total above its original HT or VAT. **The return that brings a line's returned quantity to its sold quantity credits the exact remainder**: original `line_ht` − HT already credited, original `line_vat` − VAT already credited. So a fully returned line is credited exactly what was invoiced, and the credit notes of a document never exceed it. The credit-note line stores these amounts (`credited_ht`, `credited_vat`; the generated `line_ht`/`line_vat` take them when present, SD-140, ADR-016 parity kept).
  5. **Accounting implication:** a partial credit note may differ by at most 0.01 per line from a recomputation of quantity × price; the difference is absorbed by the last return of the line. Per-line rounding on credit notes, the pro-rata and the remainder rule are part of **DR-040**: an accountant must confirm them with the invoice rounding before go-live.
- **Alternatives:** only the service sum, without a database backstop; accept the rounding drift of partial returns (Q9 a).
- **Consequences:** BR-RET-001, BR-RET-004, BR-RET-005, `database.md` §3.7 and §7, `testing.md` (concurrency), DR-040.

## ADR-131 — What can be returned, the return document and its immutability (Phase 8)
- **Status:** Accepted (user decision, 2026-11-02, Phase 8 preflight; Q5, Q7, Q10; numbering and reasons technical).
- **Decision:**
  1. A customer return is made against a **confirmed invoice or receipt**. A delivery note that is not invoiced is refused (`RETURN_REQUIRES_INVOICE`): it is invoiced first (ADR-125: its lines stay frozen), then the return is made against the invoice, whose lines carry the delivery note's lots. A quote, a credit note, a draft or a cancelled document is refused (`INVALID_STATE_TRANSITION`).
  2. **Time limit:** the setting `customer_return_max_days` (default `off`); when it holds a number of days, a return later than that many days after the original's business date is refused (`RETURN_PERIOD_EXPIRED`). No override.
  3. **The return document:** numbered `CR-YYYY-NNNNNN` (key `CR`, ADR-097), with a mandatory header reason (≤ 500 characters), permission `return.customer.create` (Administrator, Pharmacist), `Idempotency-Key`, audited `RETURN_CREATE`. It always issues exactly one credit note (`CN`, ADR-122), confirmed at once, `parent_id` = the original, same customer and credit status. A return of a sale bought by an anonymous customer stays anonymous.
  4. **Immutability:** a confirmed return and its credit note can be neither edited nor cancelled in Phase 8 (BR-RET-007). A document with a return can no longer be cancelled (`SALE_NOT_CANCELLABLE`, `blockedBy: return`, BR-RET-008).
  5. **Correcting a wrong return** is **not** assumed to be "make a new sale": a new sale would carry new prices, a new date, new lot choices and a new payment, and would not link the correction to the return. The future authorised reversal/correction workflow is registered as **OD-040** and is not built in Phase 8.
  6. The recall buyers list (BR-LOT-008) shows, per document, the sold, returned and net quantity of the lot; the returns of an invoice made from a delivery note are counted on that note's row.
- **Alternatives:** return against an uninvoiced delivery note (breaks ADR-125's frozen lines); ordinary cancellation of a return (Q10, refused).
- **Consequences:** BR-RET-007/008/009, BR-SALE-014, BR-LOT-008, BR-DOC-001, `api.md` §5.7/§5.8, `database.md`, settings seed, OD-040.

## ADR-132 — Supplier payments settle several invoices through allocations (resolves OD-016 questions 1 and 2, supplier side)
- **Status:** Accepted (user decision, 2026-11-03, Phase 9 preflight; Q1 B, Q2 B, Q11).
- **Decision:**
  1. A supplier payment is allocated to **one or several** supplier invoices through a new append-only table **`supplier_payment_allocations`** (`id`, `payment_id`, `invoice_id`, `amount > 0`, `created_at`, `created_by`), built like the customer side (SD-143). `supplier_payments` has **no `invoice_id` and no `is_advance`**: the part of a payment not allocated is the **advance**, derived, never flagged.
  2. Allocations are written when the payment is recorded, or **later**, by applying the unallocated part of an existing payment to invoices (new rows, nothing edited). Σ allocations of a payment ≤ its amount; each allocation ≤ the invoice's open amount (BR-PAY-009, `PAYMENT_ALLOCATION_EXCEEDED`). The invoice rows are locked `FOR UPDATE` in ascending id order while allocating; when an advance is applied later (or a payment reversed), the **supplier row** is locked first. A payment row cannot be locked: payment tables are append-only, granted `SELECT` and `INSERT` only, and MySQL refuses `FOR UPDATE` without `UPDATE` (user decision, 2026-11-03, Phase 9 slice 1).
  3. **Open amount of a supplier invoice** = `total_ttc` − live payment allocations − credit-note applications (ADR-133). **Supplier balance (what we owe)** = Σ recorded invoices − Σ recorded credit notes − Σ live payments, allocated or not. "Live" excludes reversed payments (ADR-135).
  4. A supplier payment: amount > 0, method `cash` / `cheque` / `bank_transfer`, reference required for cheque and transfer (BR-PAY-001), active supplier (`SUPPLIER_INACTIVE`), `Idempotency-Key`, permission `payment.supplier.create`. A **cash** payment is a `cash_movements` OUT row on the payer's **own open session** (`CASH_SESSION_REQUIRED`), refused with `CASH_INSUFFICIENT` if the session's expected cash would go below zero (as ADR-128 item 4).
- **Alternatives:** one payment per invoice, as the Source schema (A): a cheque covering three invoices becomes three fictitious payments.
- **Consequences:** BR-PAY-009/010, W1c, `api.md` §4.12, `database.md` (supplier finance), SD-143, SD-146, DR-009. The Source view `v_supplier_payables` is not ported; its definition is replaced by item 3.

## ADR-133 — Supplier invoices and credit notes; correcting a wrong entry (resolves OD-016 question 3, SD-109)
- **Status:** Accepted (user decision, 2026-11-03, Phase 9 preflight; Q3 A, Q10 A).
- **Decision:**
  1. A **supplier invoice** is recorded by the accountant (`supplierinvoice.manage`): supplier, supplier's invoice number (unique per supplier, `DUPLICATE_RESOURCE`), invoice date, due date (default `invoice_date + suppliers.payment_terms_days`), total TTC ≥ 0, optional link to a **confirmed goods receipt of that supplier**. A total that differs from the receipt's value is a **warning** in the response, never a refusal (the supplier's invoice is the reference).
  2. A **supplier credit note** is a `supplier_invoices` row with `is_credit_note = TRUE`, recorded when the supplier's *avoir* arrives, with an **optional link to a supplier return** of that supplier (`supplier_return_id`, only on credit notes; SD-109 resolved, SD-144). Nothing is generated from the return: the amount is the supplier's.
  3. A credit note lowers what we owe; it can be **applied** to invoices of the same supplier through **`supplier_credit_allocations`** (`id`, `credit_note_id`, `invoice_id`, `amount > 0`; append-only), with the same limits and locks as payments (ADR-132 item 2). An unapplied credit note still lowers the supplier balance.
  4. **No edit.** A wrong entry is **cancelled with a mandatory reason** and entered again, audited `SUPPLIER_INVOICE_CANCEL` (action added with the slice). Cancelling is allowed **only while nothing settles the document**: no live payment allocation and no credit-note application on it, and for a credit note, none of its applications; otherwise `INVOICE_HAS_SETTLEMENTS` (new code, added with the slice). The payments are reversed first (ADR-135). A cancelled document leaves the balance and keeps its number for the uniqueness check.
- **Alternatives:** credit note generated from the supplier return (B): our return has no agreed supplier price; editable until the first payment (Q10 B): breaks "confirmed means immutable".
- **Consequences:** BR-PAY-008/011, BR-PAY-015, W1c, W4 step 3, `api.md` §4.6, `database.md`, SD-109, SD-144, DR-009 resolved.

## ADR-134 — Customer payments on account, allocations and advances (resolves OD-016 question 2, customer side; completes ADR-118)
- **Status:** Accepted (user decision, 2026-11-03, Phase 9 preflight; Q2 B, Q5).
- **Decision:**
  1. A **payment on account** (`payment.customer.create`, `Idempotency-Key`) is a `customer_payments` row for an **active** customer (`CUSTOMER_INACTIVE`): amount > 0, method, reference for cheque and transfer. **Cash** needs the user's own open session and adds a `cash_movements` IN row (BR-PAY-006).
  2. It is allocated to the customer's **confirmed credit invoices** with an open amount. When the request gives no allocations, the server allocates **oldest first** (due date, then number); the screen proposes the same split and the user may change it. Σ allocations ≤ amount, each ≤ the invoice's open amount (`PAYMENT_ALLOCATION_EXCEEDED`). Whatever remains is an **advance** on account. A payment with no open invoice is allowed (all advance).
  3. **Later application:** the unallocated part of an existing payment may be applied to invoices later, by new allocation rows (permission `payment.customer.create`), under the **customer row's** lock, then the invoices' in ascending id order (the payment row cannot be locked, see ADR-132 item 2). `payment_allocations` therefore gets a surrogate `id`, and the same payment may be allocated to the same invoice twice (SD-145, a change to a Phase 7 table: the existing rows are kept).
  4. **Open amount of a credit invoice** = TTC − its credit notes − live allocations; never below zero (a credit note after full payment leaves an advance, through the balance). **Balance** stays ADR-118 item 5: confirmed credit invoices − their credit notes − live account payments, allocated or not. One query computes it, for the credit-limit check (under the customer-row lock) and the statement (DR-006).
  5. Counter payments of immediate sales are allocated to their own document at confirmation (ADR-118) and are **never** offered for later application or as advances.
  6. **Account statement** (`statement.read`) for a period: opening balance at the start date, then credit invoices, their credit notes, account payments and reversals in date order with a running balance (BR-PAY-007). Counter sales and their payments are not listed. Uninvoiced credit delivery notes are shown as a separate total (they count in the credit check, not in the balance, ADR-117).
- **Alternatives:** allocation only when recording the payment (Q2 A): advances paid before the invoice could never settle it.
- **Consequences:** BR-PAY-002/004/007, BR-PAY-012, W8, `api.md` §4.9/§4.12/§5.9, SD-145, DR-006 resolved.

## ADR-135 — Reversing a payment (Phase 9)
- **Status:** Accepted (user decision, 2026-11-03, Phase 9 preflight; Q6).
- **Decision:**
  1. A customer or supplier **payment is reversed, never edited or deleted** (BR-PAY-003): a new row with `reverses_payment_id` (unique: a payment is reversed once, otherwise `INVALID_STATE_TRANSITION`), the same amount and method, and a **mandatory reason**; permission `payment.reverse`; audited `PAYMENT_REVERSE` in the same transaction.
  2. The reversed payment and its allocations **stop counting**: its invoices reopen by the allocated amounts, and the balance goes back up. Allocation rows are not touched.
  3. **Cash:** the reversal posts the opposite cash movement (customer payment → OUT, supplier payment → IN) on the **reverser's own open session** (`CASH_SESSION_REQUIRED`), whatever session the original used (BR-CASH-004). A reversal that takes cash out is refused with `CASH_INSUFFICIENT` if the session's expected cash would go below zero.
  4. **Counter payments of immediate sales cannot be reversed** through this path (`INVALID_STATE_TRANSITION`): they are corrected by cancelling the sale (ADR-119) or by a customer return (ADR-128). Refunds (`customer_refunds`) are not payments and are not reversed here.
- **Alternatives:** a negative payment (breaks `amount > 0` and the audit trail).
- **Consequences:** BR-PAY-003, BR-PAY-013, W1c, W8 step 5, `api.md` §4.12, SD-138 (already in place for customers), SD-146.

## ADR-136 — Cash: manual movements, closing another user's session, the session report (Phase 9; completes ADR-118 item 8)
- **Status:** Accepted (user decision, 2026-11-03, Phase 9 preflight; Q8, Q9, Q7 for the report).
- **Decision:**
  1. **Manual cash movement** (`cash.manage`): direction in/out, method **cash**, amount > 0, **mandatory label** (≤ 200), posted only on the user's **own open session** (`CASH_SESSION_REQUIRED`; a closed one: `CASH_SESSION_CLOSED`). A movement out is refused with `CASH_INSUFFICIENT` if the expected cash would go below zero. Audited `CASH_MOVEMENT_CREATE` (action added with the slice). `cash_movements` gets a `manual` source in its one-source CHECK (SD-146).
  2. **Closing another user's session:** a `cash.manage` holder may close any open session (for example one left open overnight) with the counted amount; the owner closes only their own (`cash.open`). Every close records who closed it; a non-zero variance is audited `CASH_VARIANCE` as today. **No tolerance threshold.**
  3. **Session report** (`cash.manage`, and the owner for their own session): opening amount, movements grouped by source (sales, account payments, refunds, supplier payments, reversals, manual), cheques and transfers listed apart (not in expected cash), expected, counted, variance. **On screen in Phase 9**; `format=pdf` comes with printing (Phase 10, OD-020).
- **Alternatives:** manual movements on any open session (Q8 alternative); a variance tolerance setting (Q9 option).
- **Consequences:** BR-CASH-002/003/004, BR-CASH-006/007, W9, `api.md` §4.12, `security.md` §7, SD-146.

## ADR-137 — Phase 9 scope: no store credit, statements and reports on screen only
- **Status:** Accepted (user decision, 2026-11-03, Phase 9 preflight; Q4 B, Q7).
- **Decision:**
  1. **Store credit stays out** (ADR-128 item 7): a return on an immediate sale is refunded in full (BR-RET-006); keeping its value on the account of a customer who paid cash needs a new ADR if the client asks for it.
  2. The **account statement** and the **cash-session report** are screens in Phase 9; their PDFs (`format=pdf`) come with Phase 10 (OD-020).
  3. Correcting a **mistaken allocation** (moving an allocation to another invoice) is not built: a mistaken payment is reversed and recorded again (ADR-135); a mistaken credit-note application has no correction in Phase 9 and is registered as **OD-041**.
- **Consequences:** `roadmap.md` Phase 9 and 10, `api.md` (statement and report `format=pdf` marked Phase 10), OD-041.

## ADR-138 — Printing engine: HTML templates to PDF, rendered on request (resolves OD-020, engine and storage)
- **Status:** Accepted (user decision, 2026-11-04, Phase 10 preflight; Q1 A, Q3 A, Q5 A).
- **Decision:**
  1. The `printing` module renders **server-side HTML templates** (Thymeleaf) to PDF with **openhtmltopdf** (the maintained `io.github.openhtmltopdf` fork, on PDFBox). The fonts ship inside the application (**Noto Sans**, and **Noto Naskh Arabic** for Arabic text, with the library's bidi and shaping support); no font comes from the network.
  2. Documents are printed **in French** (ADR-033: the business locale is `fr-DZ`), whatever the user's interface language. Text entered in Arabic (a customer's name, an address) is rendered correctly, right to left inside its field.
  3. A PDF is **rendered on request** from the stored, immutable document and the company profile version in force on its issue date (ADR-140); **nothing is stored**. The same document always gives the same content; a template change shows on later prints.
  4. Printing needs the **permission that reads the document** (`sale.read`, `return.read`, `purchase.read`, `statement.read`, `cash.manage` or the session's owner, `lot.recall`); it writes nothing and is not audited. A PDF is rendered in **under 2 s** server side.
  5. The UI never composes a legal document; templates are tested by their **extracted text** against fixtures (testing.md).
- **Alternatives:** OpenPDF with pages built in code (rigid layouts, weak Arabic support); JasperReports (heavy, separate designer); storing each PDF at confirmation (an archive to back up, and nothing a re-render of immutable data does not give).
- **Consequences:** `backend.md` (printing), `architecture.md` (printing reads the settings module's company profile), `api.md` (PDF endpoints), `testing.md`, NFR-003.

## ADR-139 — Thermal tickets and print flows (resolves OD-020, tickets)
- **Status:** Accepted (user decision, 2026-11-04, Phase 10 preflight; Q2 A, Q12 A).
- **Decision:**
  1. A thermal **ticket is an 80 mm-wide PDF** (page height following the content), printed by the browser through the till's printer driver. No ESC/POS, no local agent. Any confirmed sales document can be printed as A4 or as a ticket (`format=a4|ticket`); the counter prints tickets.
  2. In the UI, a **PDF** button opens the document in a new tab, where the browser's print dialog prints it. At the counter, after a confirmation, **"Imprimer le ticket"** prints the ticket at once through a hidden frame. Nothing prints automatically.
- **Alternatives:** 58 mm; a width setting; ESC/POS commands through a local agent; printing automatically on confirmation.
- **Consequences:** `frontend.md` §13, `api.md` `/api/sales/{id}/pdf`, NFR-003 (checked by hand on the client's printers before Phase 10 is done).

## ADR-140 — Company profile, versioned (resolves OD-026, SD-110, DR-021)
- **Status:** Accepted (user decision, 2026-11-04, Phase 10 preflight; Q4 A).
- **Decision:**
  1. The pharmacy's identity lives in a new append-only table **`company_profiles`** (SD-147): `id`, `legal_name`, `legal_form`, `address`, `wilaya`, `phone`, `email`, `nif`, `nis`, `rc`, `ai` (article d'imposition), `approval_number` (veterinary approval), `bank_account` (RIB), `footer_text`, `logo` (PNG or JPEG, at most 256 KB) and `logo_type`, `effective_from DATETIME(6)`, `created_by`. Every field is optional except `legal_name`.
  2. A change **adds a version**, never edits one; the current profile is the latest version. A document prints the version **in force at its issue time** (`effective_from ≤ issued_at`, the latest such); a document issued before any version prints the oldest one. So a reprint never changes. A statement, a cash-session report and a recall buyers list print the version current when they are printed.
  3. The profile belongs to the `settings` module; reading the current one needs authentication; a new version needs `settings.manage` and is audited `SETTING_CHANGE` (old and new values, without the logo bytes).
  4. Until a first version exists, documents print without the identity block and with the mention "Identité de l'entreprise non renseignée".
- **Alternatives:** one row edited in place (old invoices would reprint with the new identity); keys in `app_settings` (no logo, no history).
- **Consequences:** `database.md`, SD-110 accepted, SD-147, `api.md` (company profile), `security.md`, DR-021 resolved.

## ADR-141 — Legal mentions on invoices and credit notes; customer legal numbers; stamp duty
- **Status:** Accepted (user decision, 2026-11-04, Phase 10 preflight; Q6 A, Q7 A, Q8 A).
- **Decision:**
  1. An **invoice** and a **credit note** print: the company identity (ADR-140); the customer's name, address, wilaya and legal numbers when known (item 2); the number and issue date; the due date of a credit invoice; the payment method(s) of an immediate sale; the lines (product, lot, quantity and unit, unit price, discount, total excl. tax, VAT rate); the VAT by rate; totals excl. tax, VAT and incl. tax; and the **amount in words in French** ("Arrêtée la présente facture à la somme de : …", "… le présent avoir …"), dinars and centimes. Other documents print the same header and lines without the amount in words.
  2. Customers gain three optional legal numbers, **`nis`, `rc`, `ai`** (SD-148), next to `tax_id` (the NIF), entered on the customer form with `customer.manage` and printed when present.
  3. **Stamp duty** (droit de timbre) on invoices paid in cash is **not computed** in Phase 10.
  4. The accountant confirms the list of mentions and the stamp-duty question **before go-live** (DR-043, next to DR-040); another answer means a new ADR before any real invoice is printed.
- **Alternatives:** no amount in words; computing stamp duty now (its rate and cap are not confirmed); printing the NIF only.
- **Consequences:** `database.md` (customers), `api.md` (customer fields), `frontend.md` (customer form), DR-043.

## ADR-142 — Printed withdrawal-period warnings (resolves OD-028 for printing)
- **Status:** Accepted (user decision, 2026-11-04, Phase 10 preflight; Q9 A).
- **Decision:** Printed documents follow the **on-screen rule** (ADR-121): for **any customer**, anonymous included, every line whose product has withdrawal periods prints its warning — for the line's target species, or **every species of the product** when none is given — once per product and species, as `/api/sales/{id}/withdrawal-warnings` returns them. They print on invoices, delivery notes, receipts (A4 and ticket) and quotes. A product without withdrawal data prints nothing.
- **Alternatives:** breeders only (an anonymous buyer or a veterinarian would carry no warning).
- **Consequences:** BR-SALE-010, FR-SALE-008, `testing.md`.

## ADR-143 — Phase 10 documents, reprints, drafts and cancelled documents
- **Status:** Accepted (user decision, 2026-11-04, Phase 10 preflight; Q10 A, Q11 A, Q13 A).
- **Decision:**
  1. Documents printed in Phase 10: **invoice, quote, delivery note, receipt** (A4 and ticket), **credit note** (of a customer return), **purchase order**, **customer statement**, **cash-session report**, and the **recall buyers list**. Supplier-return slips are not printed; report PDFs come with Phase 11.
  2. Every print of a document is **identical**: no "DUPLICATA" mark, no print counter, no audit of printing.
  3. Only **confirmed** documents print (`INVALID_STATE_TRANSITION` for a draft); a **cancelled** document prints with a diagonal **"ANNULÉ"** watermark and its cancellation reason. A purchase order prints once validated; a draft order is refused the same way.
- **Alternatives:** the roadmap's list without the cash report and the recall list; "DUPLICATA" from the second print, with each print audited; drafts printed with a "BROUILLON" watermark.
- **Consequences:** `api.md` (PDF endpoints), `roadmap.md` Phase 10, `frontend.md`.

## ADR-144 — Backups are taken by the application (resolves OD-021, mechanism)
- **Status:** Accepted (user decision, 2026-11-04, Phase 11 preflight; Q1 A).
- **Decision:**
  1. The `backup` module runs the backup itself: `mysqldump` with `--single-transaction`, the routines and the triggers, under the dedicated read-only `backup` database account (BR-SEC-006), its output compressed (gzip) into the backup folder. The folder, the `mysqldump` path and the account's credentials come from **environment variables**, never from settings or the repository; the folder lies outside anything the web server serves.
  2. Every run, scheduled (ADR-145) or started with **Run now** (`POST /api/backups`, `backup.manage`, audited `BACKUP_RUN`), records one `backup_log` row: status (success or failed), file name, size, start and end; a failure records its reason (never a secret) and logs an error. One backup runs at a time; a request while one runs is refused (`INVALID_STATE_TRANSITION`).
  3. A backup is downloaded by its record id (`GET /api/backups/{id}/download`, `backup.manage`), never by a path; each download is audited `BACKUP_DOWNLOAD` (BR-BKP-002).
  4. The application does **not** restore. Restoring is a written procedure (import into an empty database with the migration account, then start the application, which checks the Flyway history), rehearsed in Phase 11 and carried by the deployment procedure of Phase 12.
- **Alternatives:** an operating-system job with the application only showing the log; both mechanisms.
- **Consequences:** `backend.md` (jobs, `backup`), `api.md` §4.14, `business-rules.md` BR-BKP-001/002, `database.md` (`backup_log`), `security.md` (environment variables, audit), README (`mysqldump` on the server). Hosting, availability, RTO and who operates the server stay in OD-021 for Phase 12.

## ADR-145 — Backup schedule and retention (resolves OD-021, frequency)
- **Status:** Accepted (user decision, 2026-11-04, Phase 11 preflight; Q2 A).
- **Decision:**
  1. One backup per business day, at the first tick at or after the setting `backup_time` (default `02:00`, business time zone; catches up the same day after downtime), like the other daily jobs.
  2. The last `backup_retention_count` (default 30) **successful** backups are kept; after a successful run the older files are deleted. Their `backup_log` rows stay, marked as removed (a download then answers `NOT_FOUND`). A failed run deletes nothing.
  3. The data lost in the worst case (RPO) is one business day.
- **Alternatives:** 7 daily, 8 weekly and 12 monthly; twice a day for 14 days.
- **Consequences:** settings `backup_time`, `backup_retention_count` (validated, audited like every setting); `backend.md` jobs table.

## ADR-146 — The off-site copy is a manual download (resolves OD-021, off-site copy)
- **Status:** Accepted (user decision, 2026-11-04, Phase 11 preflight; Q3 A).
- **Decision:**
  1. The application keeps its backups on the server only; the copy away from it is a backup an administrator downloads (for example weekly, to a removable disk kept off the premises).
  2. The backups page shows the date of the last download and warns when none happened for `backup_download_reminder_days` (default 7), and when the last backup failed or is older than a day.
- **Alternatives:** a copy to a second configured folder (network share, external disk); an upload to S3-compatible storage.
- **Consequences:** setting `backup_download_reminder_days`; `frontend.md` (backups page); the deployment procedure (Phase 12) states who downloads and where the copies are kept.

## ADR-147 — Revenue and margin count invoices and receipts, net of credit notes (resolves DR-007 scope)
- **Status:** Accepted (user decision, 2026-11-04, Phase 11 preflight; Q4 A).
- **Decision:**
  1. Revenue and gross margin count confirmed (not cancelled) **invoices and receipts** at their issue date in the business time zone, **less their credit notes** at the credit note's issue date (BR-RPT-001). Quotes never count.
  2. A delivery note counts once invoiced, at the invoice's date, and only through its invoice. Confirmed delivery notes not yet invoiced are shown **apart**, as a separate total, never in revenue.
  3. Cost of sales is the cost snapshot of each sold line (`sales_document_lines.unit_cost`, per base unit); a credit note takes back the revenue and the cost of the quantity it credits. Goods discarded on return are a stock loss (`return_discard`, ADR-127), not a margin item.
- **Alternatives:** counting a delivery note at its own date, its invoice then not counted.
- **Consequences:** the rewritten views (ADR-014, DR-007), `business-rules.md` BR-RPT-001, `api.md` §4.13.

## ADR-148 — Receivables ageing and payables reports
- **Status:** Accepted (user decision, 2026-11-05, Phase 11 slice 3; the four recommended options).
- **Decision:**
  1. **Age** of a credit invoice = days past its **due date** (business date of today − due date); 0 or less is "not yet due".
  2. **Columns:** "not yet due", then the overdue columns closed by the new setting **`ageing_buckets`** = `30,60,90` (three strictly increasing positive integers, editable, ADR-044): 1–30, 31–60, 61–90, over 90 days.
  3. An invoice is aged on its **open amount** (TTC − its confirmed credit notes − allocations of payments not reversed). Advances (unallocated payments on account) and invoices credited beyond their total lower the balance in an **advance** column; the row's balance = Σ columns − advance = the balance of ADR-118, the one on the Account tab.
  4. **Uninvoiced credit delivery notes** (ADR-117) are a separate column, **not aged** and not in the balance.
  5. **Supplier payables:** the open recorded invoices of every supplier (or one) by due date, with days overdue, the unapplied credit notes and the advances; balance as BR-PAY-014.
  6. **Cash-session variances** over a period list the sessions **closed** in it (business date of closing); open sessions are not listed.
  7. Both read report views (ADR-014) that repeat the finance definitions: `v_customer_receivables` and `v_supplier_open_items` (SD-151, SD-152); tests check them against the finance service on the same data.
- **Alternatives:** age from the issue date; fixed columns; delivery notes aged with the invoices or left out; sessions opened in the period.
- **Consequences:** BR-RPT-004, `api.md` §4.13, `database.md` §2 (settings) and §5, `database-migration.md` SD-151/SD-152, `testing.md`.

## ADR-149 — Report exports: CSV for French spreadsheets, PDF tables, not audited
- **Status:** Accepted (user decision, 2026-11-05, Phase 11 exports; the three recommended options).
- **Decision:**
  1. **CSV** (`?format=csv` on every report): semicolon separator, decimal comma without grouping, dates `dd/MM/yyyy` and times `dd/MM/yyyy HH:mm` in the business time zone, UTF-8 with a byte-order mark, CRLF; French headers and labels; the rows of the JSON report, then the totals. Downloaded as an attachment, `Cache-Control: no-store`. A text cell beginning with `=`, `+`, `-`, `@`, a tab or a carriage return gets a leading apostrophe (no formula injection); numbers stay numbers.
  2. **PDF** (`?format=pdf`, rendered by `printing`, ADR-138 style): company header from the company profile, report title, filters and period, print date, the table and totals; A4 **landscape** for wide reports (ageing, payables, cash sessions, lot traceability, margin by product, valuation by product), portrait otherwise; French.
  3. Exports are **not audited**: they are read-only and gated by the report's own permission, like the screen and the existing PDFs.
- **Alternatives:** standard CSV (comma, decimal point, no BOM); portrait PDF only; auditing each export (`REPORT_EXPORT`).
- **Consequences:** BR-RPT-007, `api.md` §4.13, `architecture.md` (`printing` may call `reporting` for the PDFs), `testing.md`.

## ADR-150 — On-premises server, business-hours availability, RTO of four business hours (resolves OD-021, hosting)
- **Status:** Accepted (user decision, 2026-11-05, Phase 12 preflight; the four recommended options).
- **Decision:**
  1. **Hosting:** one Linux server **on the pharmacy's premises**, on its local network; the counter PCs reach it there, so sales do not depend on the internet link. The server sits behind a UPS.
  2. **Availability:** during **business hours** (the opening hours the client gives in the deployment record, e.g. 08:00–20:00, six days a week). Updates, migrations and restores are done outside them; no availability percentage is promised.
  3. **Recovery:** **RTO four business hours**: the last backup restored by the README procedure on a spare or repaired machine; no standby server. **RPO one business day** (ADR-145). A spare machine, or a replacement obtainable within the RTO, is part of the deployment.
  4. **Operations:** the **developer**, under a maintenance contract, installs, updates, migrates and restores, remotely (ADR-153) or on site, with a written runbook (Phase 12); the pharmacy downloads the off-site backup copies (ADR-146).
- **Alternatives:** a cloud server (every sale depends on the internet, personal data with a third party); 24/7 availability; RTO of one business day or under one hour (warm standby); operation by the client's IT provider or by pharmacy staff.
- **Consequences:** `architecture.md` §12, `requirements.md` NFR-004, DR-013 and DR-030 resolved, the deployment guide and runbook of Phase 12 (spare machine, UPS, restore within four business hours rehearsed during UAT).

## ADR-151 — Production migrations by the application's migrate command (defines ADR-018's controlled step)
- **Status:** Accepted (user decision, 2026-11-05, Phase 12 preflight).
- **Decision:**
  1. The release JAR has a **migrate mode**: started once with it, the application connects with the **migration account** only, applies the pending Flyway migrations with the same callbacks as in development (the grants of the application account, ADR-087), reports the applied versions and **exits** without serving requests. The application in normal mode keeps Flyway off and never holds the migration account (ADR-087).
  2. **Release order** (runbook, outside business hours): Run now a backup (or check that day's), stop the application, run the migrate mode, start the new version. A failed migration stops the release: restore that backup (MySQL DDL is not transactional, ADR-018).
  3. The README restore procedure uses the same migrate mode after the import, then starts the application.
- **Alternatives:** the standalone Flyway tool with a separate grants script (two tools, grants duplicated); Flyway at application start in production (the running application would hold schema-changing credentials).
- **Consequences:** backend work in Phase 12 (the migrate mode and its test), README (release and restore), `development-workflow.md` release steps, the runbook.

## ADR-152 — HTTPS on the local network with a private certificate authority
- **Status:** Accepted (user decision, 2026-11-05, Phase 12 preflight).
- **Decision:** A small **private certificate authority** is created for the pharmacy; its root certificate is installed once in the trust store of each counter PC (and browser where needed); the **reverse proxy** on the server presents a certificate it signs for the server's local name, terminates TLS, sends HSTS and forwards to the application (security.md §10). The CA's private key is kept offline by the operator, never on the server. Renewal before expiry is a runbook step with a reminder date in the deployment record.
- **Alternatives:** a self-signed server certificate trusted on each PC (every renewal touches every PC); a public certificate through a real domain and DNS validation (needs a domain and internet for renewals).
- **Consequences:** deployment guide (proxy, certificates, local name), runbook (renewal, adding a PC), `security.md` §10.

## ADR-153 — Remote maintenance through a VPN and SSH keys
- **Status:** Accepted (user decision, 2026-11-05, Phase 12 preflight).
- **Decision:** The operator reaches the server through a **WireGuard VPN** to the pharmacy network; **SSH** on the server accepts **keys only** (no password, no root login) and listens only on the local network and the VPN; nothing of the server is exposed to the internet except the VPN port. Logins are logged on the server; the operator's keys are listed in the deployment record and removed when a person leaves.
- **Alternatives:** a commercial remote-desktop tool (a third-party service in the path); on-site only (the RTO then depends on travel).
- **Consequences:** deployment guide (VPN, SSH, firewall), runbook, `security.md` (remote administration).

## ADR-154 — A user's own password change ends their other sessions (amends ADR-036)
- **Status:** Accepted (user decision, 2026-11-05, Phase 12 security review, finding 1).
- **Decision:** When users change their own password, every other session of theirs is deleted (an open session on another counter PC, or a stolen one) and the current session gets a new id, as before; a security event `other_sessions_ended` is logged. An administrator's reset and a deactivation already end all of the user's sessions (ADR-036).
- **Alternatives:** keep the other sessions until their idle or absolute timeout.
- **Consequences:** `AuthSessions`, `UserSessions.endOthersAfterCommit`; `security.md` §2.3; test `PasswordChangeTest`.

## ADR-155 — Dependency vulnerability gate in CI with OSV-Scanner
- **Status:** Accepted (user decision, 2026-11-05, Phase 12 security review, finding 2).
- **Decision:** Every CI run scans the backend's **resolved runtime dependencies** (a CycloneDX bill of materials from Maven, since the scanner cannot resolve the versions the Spring Boot parent manages from `pom.xml` alone) and `frontend/package-lock.json` with a pinned OSV-Scanner image. The build fails on an advisory of **CVSS 7.0 or more with a fixed version available**, unless it is in `.github/osv-allowlist.json` with a reason and an expiry date not yet passed; other advisories are printed. `npm audit` stays.
- **Alternatives:** OWASP Dependency-Check (slow, an NVD API key); a manual review per release.
- **Consequences:** CI job "Dependency scan", `.github/scripts/osv_gate.py`; the first run found Tomcat 11.0.24 (four Critical) and Jackson 3.1.5 (five High), fixed by Spring Boot 4.1.2; ognl 3.3.4 (Medium, Thymeleaf's, fixed templates only) printed.

## ADR-156 — No second factor for Administrators (accepted risk)
- **Status:** Accepted (user decision, 2026-11-05, Phase 12 security review, finding 3).
- **Decision:** No MFA is added. Accepted risk, given that the application is reachable only on the pharmacy's local network (ADR-150, ADR-152) and the server only through the VPN with SSH keys (ADR-153), with a 12-character password policy, common-password refusal, bcrypt and login throttling (ADR-038, ADR-039). To be revisited if the application is ever exposed beyond the local network.
- **Alternatives:** TOTP for the Administrator role.
- **Consequences:** `security.md` §15 (accepted risks).

## ADR-157 — A versioned reverse-proxy configuration, checked automatically
- **Status:** Accepted (user decision, 2026-11-05, Phase 12 security review, finding 4).
- **Decision:** The repository holds a reference reverse-proxy configuration (TLS with the private-CA certificate of ADR-152, HSTS, the frontend's security headers — Content-Security-Policy, X-Content-Type-Options, frame-ancestors, Referrer-Policy —, a coarse request-rate limit, SPA routing, `/api` to the application on `127.0.0.1`); an automated check runs the frontend under that Content-Security-Policy so a violation fails the build.
- **Alternatives:** headers and limits described in the guide only, written by the operator on the server.
- **Consequences:** a later Phase 12 slice (deployment); `security.md` §11.

# Part B — Open Decisions

Each Open Decision lists the question, the options, an optional author's leaning (**not a decision**), the phase it blocks, and where it matters. Until decided, **no code may depend on an assumption** about it.

## OD-001 — Authentication mechanism
- **Status:** Resolved 2026-10-20 → **ADR-036** (option B).
- **Question:** Stateless JWT (short-lived access token + rotating refresh token) or server-side session cookie (Spring Session)?
- **Options:** (A) JWT access token in memory + refresh token in `HttpOnly; Secure; SameSite=Strict` cookie, refresh tokens stored hashed in a new table; (B) opaque session id in `HttpOnly` cookie + CSRF protection, sessions in DB.
- **Leaning:** (A) for API-first design; (B) is simpler and gives instant revocation. Both satisfy FR-SEC-001.
- **Blocks:** Phase 1. **Related:** `security.md`, `api.md` §4.1, BR-SEC-001.

## OD-002 — Which sales documents move stock, and when
- **Status:** Resolved 2026-10-31 → **ADR-117** (option B).
- **Question:** Does a **delivery note** deduct stock, or only an **invoice/receipt**? If an invoice is created from a delivery note, how is double deduction prevented? Are delivery notes part of lot recall?
- **Why it matters:** `sales_doc_type` includes `quote`, `delivery_note`, `invoice`, `receipt`, `credit_note`; Source views `v_sales_margin` and function `recall_lot` consider only `invoice` and `receipt`, so goods delivered on an un-invoiced delivery note would be missed by a recall.
- **Options:** (A) Only `invoice`/`receipt` deduct stock; delivery note is informational. (B) Delivery note deducts; invoice created from it does not (flag on lines). 
- **Blocks:** Phase 7. **Related:** BR-SALE-002, BR-SALE-005, BR-LOT-008, DR-004.

## OD-003 — Payment capture at the counter and the customer-balance definition
- **Status:** Resolved 2026-10-31 → **ADR-118** (option A; DR-003 option a).
- **Question:** How are payments for **immediate (non-credit) sales** and **anonymous receipts** recorded? `customer_payments.customer_id` is `NOT NULL` and `sales_documents` carries no payment method, yet `cash_movements` need a method. And what exactly is a *customer balance* (credit invoices only? all invoices minus all payments?).
- **Why it matters:** `v_customer_balance` subtracts **all** `customer_payments` and **all** credit notes but adds only **credit** invoices → balance becomes wrong as soon as any cash sale is paid through `customer_payments` or a credit note is issued against a cash sale.
- **Options:** (A) Every sale creates a payment + allocation (uniform model; anonymous customer record allowed or `customer_id` nullable). (B) Immediate sales are "paid on confirmation" with a payment-method column on the document; `customer_payments` only for account settlements. 
- **Blocks:** Phase 7 (counter payment) and Phase 9. **Related:** BR-PAY-004…006, BR-CASH-003, DR-005, DR-006.

## OD-004 — Cancellation of confirmed documents
- **Status:** Resolved 2026-10-31 → **ADR-119** (restricted cancellation).
- **Question:** May a *confirmed* invoice/receipt be cancelled, or only reversed by a credit note? If cancellable, which movement type reverses the stock (the Source enum has no `sale_cancellation`/`receipt_cancellation`)?
- **Options:** (A) Cancellation allowed with permission `sale.cancel`; add movement type `sale_cancellation`. (B) No cancellation after confirmation; use credit note + customer return. 
- **Spec hint:** audit of "annulations de factures" (§2.7) implies cancellation exists.
- **Blocks:** Phase 7. **Related:** BR-SALE-014, ADR-008.

## OD-005 — Customer-return disposition and refund
- **Status:** Resolved 2026-11-02 → **ADR-127** (question 1, option C), **ADR-128** (question 2), **ADR-129** (question 3: never forced, refused instead); the rest of the Phase 8 preflight: ADR-130, ADR-131, OD-040.
- **Question:** (1) For `discard`, is there any stock movement? (2) How is a return on a **cash/anonymous** sale refunded (cash movement out)? (3) May restock be forced to `discard` automatically for expired/recalled lots?
- **Options (1):** none (return never enters stock) vs. `customer_return` (+) immediately followed by `adjustment_expiry/breakage` (−) for full traceability.
- **Blocks:** Phase 8. **Related:** BR-RET-002…006.

## OD-006 — Price resolution precedence
- **Status:** Resolved 2026-10-24 → **ADR-107**.
- **Question:** When several prices apply (product price per base unit, per-unit `product_units.sale_price`, `customer_product_prices`, `customer_categories.discount_pct`, manual line discount), what is the precedence and can they combine?
- **Blocks:** Phase 6/7. **Related:** BR-SALE-006, BR-SALE-007.

## OD-007 — Rounding, VAT computation, decimals
- **Status:** Resolved 2026-10-24 → **ADR-093**.
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
- **Status:** Resolved 2026-10-31 → **ADR-120** (option A).
- **Question:** The Spec says the system *proposes* FEFO. May a seller choose a different lot? With what permission/reason?
- **Leaning:** Allow only with permission `sale.override_fefo` and a recorded reason; always allow skipping non-sellable lots.
- **Blocks:** Phase 7. **Related:** BR-LOT-005.

## OD-011 — Credit-limit semantics
- **Status:** Resolved 2026-10-24 → **ADR-108** (Phase 6 stores and audits the limit; the check comes with sales in Phase 7; payments enter the balance in Phase 9).
- **Question:** `credit_limit DEFAULT 0` — does 0 mean *no credit allowed* or *unlimited*? Are overdue invoices (`due_date`) a blocking condition? Is the limit checked against *balance + new invoice*?
- **Blocks:** Phase 6 (stored meaning of the limit), Phase 7 (the check) and Phase 9 (payments in the balance). **Related:** BR-PAY-005, BR-CUST-002.

## OD-012 — Inventory Count concurrency and scope
- **Status:** Resolved 2026-10-22 → **ADR-072** (concurrency, refined by the user), **ADR-073** (scope), **ADR-074** (found lots), **ADR-075** (closing).
- **Question:** While a count is open, are sales/receipts frozen, or is the variance computed against the theoretical quantity **at count time** (delta of movements since snapshot)? How is a *cycle* count scoped (category, shelf, ABC)? How are lots found physically but unknown to the system handled?
- **Blocks:** Phase 4. **Related:** BR-INV-005.

## OD-013 — Reorder proposal algorithm
- **Status:** Resolved 2026-10-22 → **ADR-076** (trigger), **ADR-077** (quantity), **ADR-078** (schedule), **ADR-079** (duplicates; supplier and open orders in Phase 5).
- **Question:** Which threshold triggers (`reorder_point` per Source view vs. `min_stock`)? Suggested quantity (e.g. `max_stock − current`)? Which supplier is proposed (cheapest in `supplier_price_history`)? Generated on every movement or by a scheduled job?
- **Blocks:** Phase 4/5. **Related:** BR-STOCK-010.

## OD-014 — Non-conforming goods at receipt
- **Status:** Resolved 2026-10-24 → **ADR-095**.
- **Question:** `goods_receipt_lines.is_conforming = false`: does the stock enter the lot as *blocked*, not enter at all, or enter normally and be returned later?
- **Blocks:** Phase 5. **Related:** BR-PURCH-006.

## OD-015 — Over-receipt and receipt without purchase order
- **Status:** Resolved 2026-10-24 → **ADR-096**.
- **Question:** May received quantity exceed the ordered quantity? May goods be received without a PO (`purchase_order_id` is nullable)?
- **Blocks:** Phase 5. **Related:** BR-PURCH-004.

## OD-016 — Supplier settlements
- **Status:** Resolved 2026-11-03 → **ADR-132** (several invoices per payment, advances applied later), **ADR-133** (credit notes, optional link to the supplier return), **ADR-134** (customer advances).
- **Question:** `supplier_payments.invoice_id` is single-valued: can one payment settle several invoices? How are *advances* (`invoice_id NULL`) later applied to invoices? Should a supplier return generate/link a supplier credit note (no link exists in the schema)?
- **Blocks:** Phase 9. **Related:** BR-PAY-009…011, DR-009.

## OD-017 — Document numbering policy
- **Status:** Resolved 2026-10-24 → **ADR-097**.
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
- **Status:** Resolved 2026-11-04 → **ADR-138**, **ADR-139** (legal mentions: ADR-141).
- **Question:** PDF generation library; thermal ticket width (58/80 mm) and method (PDF printed by browser vs. ESC/POS); templates; legal mentions on invoices; who owns document layout.
- **Blocks:** Phase 10. **Related:** NFR-003.

## OD-021 — Hosting, availability and backup policy
- **Status:** **Resolved.** Backup 2026-11-04 → **ADR-144** (mechanism), **ADR-145** (schedule, retention), **ADR-146** (off-site copy); hosting, availability, RTO and operations 2026-11-05 → **ADR-150** (and ADR-151…153 for the migrations, HTTPS and remote access).
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
- **Status:** Resolved 2026-11-04 → **ADR-140**.
- **Question:** The pharmacy's own legal identity (name, address, tax ids, logo, footer text) required on invoices is not in the schema. Store in `app_settings` or a dedicated `company_profile` table?
- **Blocks:** Phase 10.

## OD-027 — Lot status semantics
- **Status:** Resolved 2026-10-22 → **ADR-059** (expired/depleted computed) and **ADR-060** (recall final, unblock with `lot.unblock`).
- **Question:** May a `recalled` lot be un-recalled? Who can unblock a `blocked` lot? Is `expired` status materialised by a daily job or purely derived from `expiry_date`? What happens to `depleted` lots reached by a customer return (reactivation)?
- **Blocks:** Phase 3. **Related:** BR-LOT-009.

## OD-028 — Applicability of withdrawal-period warnings
- **Status:** Resolved: on screen 2026-10-31 → **ADR-121**; printing 2026-11-04 → **ADR-142**.
- **Question:** Print warnings only on documents for customers of type *breeder*, or on all documents containing products that have withdrawal periods? What when `target_species_id` is not specified on the line (Source view then lists all species)?
- **Blocks:** Phase 10. **Related:** BR-SALE-010.


## OD-029 — Lot cost on repeated receipt of the same lot
- **Status:** Resolved 2026-10-24 → **ADR-098**.
- **Question:** A lot is unique per (product, lot number) (ADR-070). If the same lot is received again at a **different unit cost**, what happens to `lots.unit_cost`? Valuation (FR-RPT-002) and margin (FR-RPT-001) depend on it.
- **Options:** (A) weighted-average cost recomputed on each receipt; (B) keep the first cost and ignore the new one; (C) forbid a second receipt of the same lot at a different cost (force a distinct lot identity); (D) track cost per receipt line and value by FIFO layers (needs schema change).
- **Blocks:** Phase 5. **Related:** BR-LOT-002, BR-SALE-009, `products.purchase_price` ("last/avg cost" is ambiguous in the Source).

## OD-030 — Test MySQL container lifecycle
- **Status:** Resolved 2026-10-20 → **ADR-035** (option A).
- **Question:** Testcontainers MySQL is a bean of `TestcontainersConfiguration`, so one container (~10 s start) runs per distinct Spring test-context configuration. Phase 0 has two (plain `@SpringBootTest`, and `RANDOM_PORT` + `RestTestClient`); later phases add more (`@DataJpaTest`, `@MockitoBean` variants). Measured 2026-10-20: warm `./mvnw verify` 45 s, of which ~20 s container start-up; the reported 7 min 46 s run was a cold run (first Maven dependency download and first pull of the 1.12 GB `mysql:8.4` image), not a test-design problem.
- **Options:** (A) keep one container per context (current; strongest isolation, cost grows with each new context); (B) one container per JVM, started once and exposed as `JdbcConnectionDetails` (not as a container bean, so closing one context cannot stop it under another), each context sharing the database — needs a cleanup convention and a dedicated container for "Flyway on an empty DB" migration tests; (C) (A) plus opt-in Testcontainers reuse on developer machines only (`testcontainers.reuse.enable=true` in `~/.testcontainers.properties`, never in CI), which keeps a container alive across runs.
- **Leaning:** `[REC]` keep (A) now; adopt (B) when the integration suite exceeds ~4 contexts, together with the cleanup convention of `testing.md` §3.
- **Blocks:** nothing in Phase 0; decide before Phase 4 (first stock/locking suites). **Related:** ADR-030, `testing.md` §1 (principle 7), DR-034.

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
- **Status:** Resolved 2026-10-23 → **ADR-092** (option B: warning on the form, no CHECK).
- **Question:** BR-PROD-005 / BR-STOCK-009 only require `max_stock ≥ min_stock`. A `max_stock` at or below `reorder_point` makes reorder proposals impossible; Phase 4 reports such products as *thresholds inconsistent* (ADR-077 amendment) but still accepts them. Should creating or editing a product refuse it?
- **Options:** (A) refuse it (`VALIDATION_FAILED`, field `maxStock`, new code, plus a DB CHECK through a migration); (B) accept it with a warning on the product form; (C) keep the current behaviour (reported only at generation).
- **Leaning:** none recorded; for the user.
- **Blocks:** nothing in Phase 4; to decide before Phase 5 uses the thresholds for purchase orders. **Related:** BR-PROD-005, BR-STOCK-009, BR-STOCK-010, ADR-077.

## OD-035 — Sales document keys and numbering moment
- **Status:** Resolved 2026-10-31 → **ADR-122** (option A).
- **Question:** ADR-097 left the sales keys and the moment of numbering to Phase 7, while `sales_documents.number` was `NOT NULL` although drafts exist. Number at confirmation or at creation?
- **Blocks:** Phase 7. **Related:** BR-DOC-001, ADR-097.

## OD-036 — Precision of a sales line price
- **Status:** Resolved 2026-10-31 → **ADR-123** (option A).
- **Question:** `sales_document_lines.unit_price` was documented with 2 decimals while prices per base unit have 4 (ADR-049). 2 or 4 decimals?
- **Blocks:** Phase 7. **Related:** ADR-049, ADR-094.

## OD-037 — Prescription-only products
- **Status:** Resolved 2026-10-31 → **ADR-124** (option A); the client's answer is a go-live blocker (DR-041).
- **Question:** May some products be sold only on a veterinary prescription, and what must the sale then record or check? The model has no such attribute and the Spec is silent (raised 2026-10-25 with the demo data; question to the client still pending).
- **Options:** (A) deliver Phase 7 without it, keep the question as a **go-live blocker**, and add it later by a migration if the client requires it; (B) add a product flag now and refuse the sale without a prescription reference; (C) add a product flag now with a warning only.
- **Leaning:** `[REC]` (A): (B) and (C) would invent a requirement before the client answers, and adding it later does not change confirmed documents.
- **Blocks:** Phase 7 (decision on how to proceed), go-live (the client's answer). **Related:** BR-SALE-*, W2 step 6.

## OD-038 — Commercial rules of a delivery note
- **Status:** Resolved 2026-10-31 → **ADR-125** (option A).
- **Question:** ADR-117 makes a delivery note move stock and ADR-118 takes payment on an immediate sale, but neither says: (1) when a non-credit delivery note is paid; (2) whether an invoice can cover part of a delivery note, or several; (3) whether invoicing a delivery note is checked against the limit again; (4) what cancelling such an invoice does.
- **Options:** (A) a delivery note takes no payment; the invoice inherits its credit status, and is paid in full at its confirmation when non-credit; one whole delivery note gives exactly one invoice, with lines, lots and prices frozen; no new limit check (the amount is already counted and the goods have left); cancelling the invoice posts no stock movement and makes the delivery note invoiceable again. (B) as (A), but partial invoicing is allowed, tracked per line. (C) as (A), but several delivery notes may be grouped into one invoice, which needs a link table because `parent_id` holds one parent.
- **Leaning:** `[REC]` (A): simplest, and it fits the single `parent_id`. Partial or grouped invoicing can be added later.
- **Blocks:** Phase 7. **Related:** ADR-117, ADR-118, ADR-119, BR-SALE-001, BR-SALE-002.

## OD-039 — The Pharmacist sells but cannot take cash
- **Status:** Resolved 2026-10-31 → **ADR-126** (option A).
- **Question:** The matrix (ADR-037) gives the Pharmacist `sale.create` but not `cash.open`. Since a cash payment needs the cashier's own open session (ADR-118), a Pharmacist could never take a cash sale. Is that intended?
- **Options:** (A) grant `cash.open` to the Pharmacist, by a migration; (B) keep the matrix: a Pharmacist only makes credit, cheque and transfer sales (`CASH_SESSION_REQUIRED` on cash).
- **Leaning:** `[REC]` (A): the Pharmacist is a named actor of the counter workflow (W2).
- **Blocks:** Phase 7. **Related:** ADR-037, BR-SALE-013, BR-PAY-006.

## OD-040 — Authorised reversal or correction of a confirmed customer return
- **Status:** OPEN (registered 2026-11-02, Phase 8 preflight Q10). **Not built in Phase 8.**
- **Question:** How is a confirmed customer return that was wrong (wrong lot, quantity, disposition or refund) corrected, while keeping the whole financial and inventory trail? ADR-131 forbids editing or cancelling it, and a new sale is not a valid correction in general (new prices, date, lots and payment, no link to the return).
- **Requirement to keep:** whatever is chosen must post new compensating rows only (movements, refund or payment rows, a document linked to the return and its credit note), never change or delete the return, its credit note, movements or refunds; it needs its own permission, a mandatory reason and an audit entry in the same transaction; it must respect stock that has meanwhile left the lot (BR-STOCK-001) and closed cash sessions (BR-CASH-004).
- **Options (to be studied):** (A) a whole-return reversal document (`customer_return_reversal`): reverse movements, a debit document cancelling the credit note, the refund taken back; (B) a line-level correction document; (C) stay without one, with corrections only by adjustments and manual cash movements (Phase 9).
- **Blocks:** nothing in Phase 8; to decide before go-live if the client needs it (Phase 9 or later). **Related:** ADR-127…131, BR-RET-007, BR-PAY-003.

## OD-041 — Correcting a mistaken allocation
- **Status:** OPEN (registered 2026-11-03, Phase 9 preflight, ADR-137 item 3). **Not built in Phase 9.**
- **Question:** A payment or a supplier credit note applied to the wrong invoice: may an allocation be undone (a counter-row) and applied again, by whom, with what audit? In Phase 9 a mistaken payment is reversed and recorded again (ADR-135); a mistaken credit-note application cannot be corrected.
- **Options:** (A) allocation counter-rows with a reason and a permission; (B) keep reversal of the whole payment only, and allow cancelling a credit note's applications by cancelling and re-entering the credit note.
- **Blocks:** nothing in Phase 9; to be answered before go-live if the accountant needs it. **Related:** BR-PAY-002, ADR-132…135.

---

# Part C — Decision backlog by phase

| Phase | Decisions that must be *Accepted / decided* before it starts |
|---|---|
| 0 Foundation | ~~OD-024~~ → ADR-032, ~~OD-008~~ → ADR-033, ADR-034; ADR-009/010/013/018/019/023/024/025/026/030/031 confirmed Accepted 2026-10-20 |
| 1 Auth | ~~OD-001~~ → ADR-036, ~~OD-023~~ → ADR-037; ADR-017/020/021/027 confirmed Accepted; ADR-038–041, ~~OD-031~~ → ADR-042 (all 2026-10-20) |
| 2 Catalogue | ~~OD-018~~ → ADR-047/048, ~~OD-022~~ → ADR-049/050, OD-025 for SKU → ADR-051, ADR-052, ADR-053, ~~OD-032~~ → ADR-054 (all 2026-10-21) |
| 3 Lots & Units | ~~OD-009~~ → ADR-055/056, ~~OD-019~~ → ADR-057, ~~OD-025~~ → ADR-058, ~~OD-027~~ → ADR-059/060; ADR-061…068 (all 2026-10-22) |
| 4 Inventory & Stock | ~~OD-012~~ → ADR-072…075, ~~OD-013~~ → ADR-076…079, ~~OD-030~~ → ADR-035, ADR-012 (accepted, amended), ADR-028; ADR-080…089 (all 2026-10-22); ADR-090, ADR-091, ADR-077 amendment, ~~OD-034~~ → ADR-092 (2026-10-23) |
| 5 Purchasing | ~~OD-007~~ → ADR-093, ~~OD-014~~ → ADR-095, ~~OD-015~~ → ADR-096, ~~OD-017~~ → ADR-097 (ADR-011 accepted), ~~OD-029~~ → ADR-098; ADR-094, ADR-099…106 (all 2026-10-24) |
| 6 Customers | ~~OD-006~~ → ADR-107, ~~OD-011~~ → ADR-108 (both 2026-10-24); ADR-109…114 (2026-10-25) |
| 7 Sales / POS | ~~OD-002~~ → ADR-117, ~~OD-003~~ → ADR-118, ~~OD-004~~ → ADR-119, ~~OD-010~~ → ADR-120, ~~OD-028 (screen)~~ → ADR-121, ~~OD-035~~ → ADR-122, ~~OD-036~~ → ADR-123, ~~OD-037~~ → ADR-124, ~~OD-038~~ → ADR-125, ~~OD-039~~ → ADR-126, ADR-115, ADR-116 (all 2026-10-31); ~~OD-006~~ → ADR-107, ~~OD-011~~ → ADR-108 (the credit check), ~~OD-033~~ → ADR-071 |
| 8 Returns | ~~OD-005~~ → ADR-127, ADR-128, ADR-129; ADR-130, ADR-131 (all 2026-11-02); OD-040 registered, not blocking |
| 9 Payments / Finance | ~~OD-003~~ → ADR-118, ~~OD-016~~ → ADR-132, ADR-133, ADR-134; ADR-135, ADR-136, ADR-137 (all 2026-11-03); ~~OD-011~~ → ADR-108 (payments in the balance); OD-041 registered, not blocking |
| 10 Documents & Printing | ~~OD-020~~ → ADR-138, ADR-139, ~~OD-026~~ → ADR-140, ~~OD-028 (printing)~~ → ADR-142, ~~OD-008~~ → ADR-033 (documents in French, ADR-138); ADR-141, ADR-143 (all 2026-11-04) |
| 11 Reports & Audit | ~~OD-021 (backup)~~ → ADR-144, ADR-145, ADR-146; ~~DR-007 (scope)~~ → ADR-147 (all 2026-11-04); ADR-148 (2026-11-05, ageing and payables); ADR-149 (2026-11-05, exports); margin scope follows ADR-117, ADR-118 |
| 12 Testing, Security, Deployment | ~~OD-021 (hosting, availability, RTO, operations)~~ → ADR-150, ADR-151 (migrations), ADR-152 (HTTPS), ADR-153 (remote access) (all 2026-11-05); ADR-036 hardening review → ADR-154…157 (2026-11-05) |
| 13 Dark theme | ADR-069 (decided 2026-10-22) |

**Backlog items (not open decisions yet; each waits for a question to the client):**

| Item | Raised | Linked to | Status |
|---|---|---|---|
| Prescription-only status for products: whether some products may only be sold on a veterinary prescription, and what the sale must then record or check | 2026-10-25, demo data (the model has no such attribute; nothing was invented) | Phase 7 — confirmation of a sale (BR-SALE-*, W2 step 6) | ~~OD-037~~ → ADR-124: Phase 7 is built without it; the client's answer is a go-live blocker (DR-041) |
