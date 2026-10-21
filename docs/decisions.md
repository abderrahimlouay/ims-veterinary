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
- **Status:** Accepted (project brief). Minimum features: **MySQL 8.0.16+** (enforced `CHECK` constraints, functional indexes, `SKIP LOCKED`). Target release line: **8.4 LTS** `[REC]` — MySQL 8.0 reached end of life in April 2026, so a new project should not start on it (verify at Phase 0, **OD-024**).
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
- **Status:** Proposed
- **Decision:** Every stock-changing use case runs in one `@Transactional` boundary and: (1) locks the affected `lots` rows with `SELECT … FOR UPDATE` **ordered by `lots.id`** (avoids deadlocks); (2) validates status/expiry/quantity; (3) inserts `stock_movements` rows; (4) applies the same signed delta to `lots.quantity_on_hand`; (5) updates `lots.status` (`depleted`/`active`); (6) persists the business document and the audit entry. `CHECK (quantity_on_hand >= 0)` is the final safety net.
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
- **Consequences:** `api.md` §4.14, BR-LOT-010, `database.md` §3.1; `BusinessSettings` (common port) is implemented by the `settings` module.

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
- **Question:** While a count is open, are sales/receipts frozen, or is the variance computed against the theoretical quantity **at count time** (delta of movements since snapshot)? How is a *cycle* count scoped (category, shelf, ABC)? How are lots found physically but unknown to the system handled?
- **Blocks:** Phase 4. **Related:** BR-INV-005.

## OD-013 — Reorder proposal algorithm
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
- **Question:** Index-backed prefix `LIKE` vs. `FULLTEXT` (ngram parser) vs. external engine; accent-insensitive French/Arabic matching; how to search by **animal species** given the schema only links species to products through *withdrawal periods* (no "target species" relation for products without withdrawal data).
- **Options for species:** (A) use `product_withdrawal_periods` only; (B) add `product_species` table.
- **Blocks:** Phase 2 (catalogue search) and 7. **Related:** NFR-002, DR-010.

## OD-019 — Barcode per packaging unit
- **Question:** `product_barcodes` links to the product only. A box and a single blister of the same product normally have different barcodes: should `product_barcodes` get a `unit_id`? What quantity does one scan add?
- **Blocks:** Phase 3. **Related:** FR-PROD-003, DR-011.

## OD-020 — Printing approach
- **Question:** PDF generation library; thermal ticket width (58/80 mm) and method (PDF printed by browser vs. ESC/POS); templates; legal mentions on invoices; who owns document layout.
- **Blocks:** Phase 10. **Related:** NFR-003.

## OD-021 — Hosting, availability and backup policy
- **Question:** On-premises server vs. cloud; availability target; backup frequency/retention/RPO/RTO; where backups are stored; who restores; is the backup performed by the application or by an external operating-system job (the app then only records `backup_log`)?
- **Blocks:** Phase 12 (deployment); backup design touches Phase 11. **Related:** FR-AUD-002, NFR-004.

## OD-022 — Quantity and price precision per unit
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
- **Status:** Partly resolved 2026-10-20: `username` → **ADR-041** (insensitive). Still open for `sku`, `lot_number` and other codes.
- **Question:** Under `utf8mb4_0900_ai_ci`, are `LOT-a1` and `LOT-A1` the same lot? Should `sku`, `lot_number`, `username` use a case-sensitive collation?
- **Blocks:** Phase 3. **Related:** ADR-024.

## OD-026 — Company identity and document header data
- **Question:** The pharmacy's own legal identity (name, address, tax ids, logo, footer text) required on invoices is not in the schema. Store in `app_settings` or a dedicated `company_profile` table?
- **Blocks:** Phase 10.

## OD-027 — Lot status semantics
- **Question:** May a `recalled` lot be un-recalled? Who can unblock a `blocked` lot? Is `expired` status materialised by a daily job or purely derived from `expiry_date`? What happens to `depleted` lots reached by a customer return (reactivation)?
- **Blocks:** Phase 3. **Related:** BR-LOT-009.

## OD-028 — Applicability of withdrawal-period warnings
- **Question:** Print warnings only on documents for customers of type *breeder*, or on all documents containing products that have withdrawal periods? What when `target_species_id` is not specified on the line (Source view then lists all species)?
- **Blocks:** Phase 10. **Related:** BR-SALE-010.


## OD-029 — Lot cost on repeated receipt of the same lot
- **Question:** A lot is unique per (product, lot number, expiry date). If the same lot is received again at a **different unit cost**, what happens to `lots.unit_cost`? Valuation (FR-RPT-002) and margin (FR-RPT-001) depend on it.
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

---

# Part C — Decision backlog by phase

| Phase | Decisions that must be *Accepted / decided* before it starts |
|---|---|
| 0 Foundation | ~~OD-024~~ → ADR-032, ~~OD-008~~ → ADR-033, ADR-034; ADR-009/010/013/018/019/023/024/025/026/030/031 confirmed Accepted 2026-10-20 |
| 1 Auth | ~~OD-001~~ → ADR-036, ~~OD-023~~ → ADR-037; ADR-017/020/021/027 confirmed Accepted; ADR-038–041, ~~OD-031~~ → ADR-042 (all 2026-10-20) |
| 2 Catalogue | OD-018, OD-022 |
| 3 Lots & Units | OD-009, OD-019, OD-025, OD-027 |
| 4 Inventory & Stock | OD-012, OD-013, ~~OD-030~~ → ADR-035, ADR-012, ADR-028 |
| 5 Purchasing | OD-007, OD-014, OD-015, OD-017, OD-029 |
| 6 Customers | OD-006, OD-011 |
| 7 Sales / POS | OD-002, OD-003, OD-004, OD-010, OD-006 |
| 8 Returns | OD-005 |
| 9 Payments / Finance | OD-003, OD-011, OD-016 |
| 10 Documents & Printing | OD-020, OD-026, OD-028, OD-008 |
| 11 Reports & Audit | OD-021 (backup), OD-002 (margin scope), OD-003 |
| 12 Testing, Security, Deployment | OD-021, ADR-036 hardening review |
