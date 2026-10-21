# Implementation Roadmap — IMS Vétérinaire

> **Status:** Phase 0 implemented (2026-10-20), awaiting user approval; Phases 1–12 not started
> **Scope:** ordered plan for Claude Code to implement.
> **Last updated:** 2026-10-20

Tags: `SRC` Spec · `REC` recommendation · `OD-nnn` open decision · `DR-nnn` review item.

## 1. How to use this roadmap

1. Phases are implemented **in order**; a phase starts only when its prerequisites are complete **and** the decisions listed for it (Part C of `decisions.md`) are *Accepted*.
2. Each phase ends with its completion criteria met and the Definition of Done in `testing.md` §11.
3. Each phase is delivered as small vertical slices (migration → backend → tests → frontend) following `development-workflow.md`.
4. An OPEN DECISION that blocks a phase is resolved by the user **before** the phase starts; Claude Code must not decide silently.
5. Phase-independent rules: no application code before Phase 0 is explicitly requested; every migration is small (ADR-018); every phase updates the docs it changes.

## 2. Phase overview

| Phase | Name | Main outcome | Decisions needed before start |
|---|---|---|---|
| 0 | Foundation | repo, build, CI, MySQL baseline, error handling, test harness | OD-024, OD-008, ADR confirmation |
| 1 | Security & administration | login, users, roles/permissions, audit, settings | ~~OD-001~~ ADR-036, ~~OD-023~~ ADR-037, ADR-027 (decided 2026-10-20) |
| 2 | Catalogue | products, reference data, search | OD-018, OD-022 |
| 3 | Lots & units | packaging units, barcodes, lots, status, expiry rules, FEFO allocator | OD-009, OD-019, OD-025, OD-027 |
| 4 | Inventory & stock | ledger, stock levels, adjustments, opening stock, counts, reorder proposals | OD-012, OD-013 |
| 5 | Purchasing | suppliers, purchase orders, goods receipts, supplier returns, numbering | OD-007, OD-014, OD-015, OD-017, OD-029 |
| 6 | Customers | customers, categories, prices, credit-limit data | OD-006, OD-011 |
| 7 | Sales / POS | quotes, delivery notes, invoices, counter sale, FEFO selling | OD-002, OD-003, OD-004, OD-010 |
| 8 | Returns | customer returns, credit notes, restock/discard | OD-005 |
| 9 | Payments & finance | payments, allocations, balances, statements, cash sessions, supplier invoices | OD-003, OD-011, OD-016 |
| 10 | Documents & printing | PDFs, tickets, withdrawal warnings on documents | OD-020, OD-026, OD-028 |
| 11 | Reports & audit | reports, exports, backup | OD-021 |
| 12 | Hardening & deployment | performance, security review, UAT, deployment procedure | OD-021, ADR-036 hardening |

**Dependency adjustment (DR-003).** The POS in Phase 7 needs cash/anonymous-sale payment capture (OD-003) and a cash session (BR-SALE-013, BR-PAY-006), which belong to Phase 9. Two options, to be chosen when OD-003 is decided: **(a)** deliver a minimal counter-payment and cash-session slice inside Phase 7 (recommended `[REC]`), or **(b)** reorder so Phase 9's payment/cash core precedes Phase 7. The plan below assumes (a).

---

## Phase 0 — Foundation
- **Objective:** a buildable, testable, empty skeleton honouring the architecture.
- **Prerequisites:** documentation reviewed; OD-024 (versions) and OD-008 (i18n structure) decided — done 2026-10-20 as ADR-032, ADR-033 (and ADR-034 module list).
- **Features:** none for end-users; health endpoint; error handling; trace id.
- **Database work:** Flyway baseline; MySQL 8.4 container for tests; collation/charset/SQL-mode settings (`database-migration.md` §9); empty-DB migration test.
- **Backend work:** Maven project, module packages (`backend.md` §2), `common` (errors/Problem Details, Clock, decimal serialisation, trace id), architecture tests, profiles and configuration, JSON logging.
- **Frontend work:** Vite + TypeScript, router shell, token CSS/Tailwind build, API client, error normaliser, test setup (Vitest, MSW, Playwright smoke).
- **Tests:** build, architecture rules, health, Problem Details mapping, migration on empty DB.
- **Completion criteria:** CI green on empty project; documented commands to build/test/run; no secrets in repo; NFR-008 verified for the baseline.
- **Delivered (2026-10-20):** branches `phase-0/common-backend-skeleton`, `phase-0/common-mysql-baseline`, `phase-0/common-errors-trace-clock`, `phase-0/common-health-logging`, `phase-0/frontend-shell`, `phase-0/ci-docs`. Commands in `README.md`. Deferred: Maven dependency-vulnerability scan (Phase 12); self-hosted fonts incl. an Arabic face (with the first real screens); semantic expiry/stock-status tokens (Phase 3).

## Phase 1 — Security & administration
- **Objective:** nobody can use the system without authenticating; permissions enforced server-side.
- **Prerequisites:** Phase 0; decided 2026-10-20: ADR-036 (sessions, was OD-001), ADR-037 (matrix, was OD-023), ADR-027, ADR-017, ADR-038–041.
- **Features:** login/logout/me/change password; users; roles and permissions; audit log write/read; application settings (alert thresholds, default VAT, locale).
- **Database work:** users, roles, permissions, role_permissions, audit_log, app_settings (renamed key columns, SD register); seed roles and permissions; append-only protection of audit rows.
- **Backend work:** `auth`, `users`, `audit`, `settings` modules; Spring Security chain; `@PreAuthorize` convention; brute-force limiter; password policy; `AuditRecorder`.
- **Frontend work:** login page, guards, app layout with permission-filtered menu, account page, users/roles/settings/audit admin pages.
- **Tests:** BR-SEC-*, BR-AUDIT-*; endpoint × role matrix framework; e2e J1, J11.
- **Completion criteria:** every endpoint authenticated by default (deny-by-default test); passwords hashed; audit rows immutable; permission matrix test passes.

## Phase 2 — Catalogue
- **Objective:** maintain the product master data and find products quickly.
- **Prerequisites:** Phase 1; OD-018 (search/species), OD-022 (precision).
- **Features:** products CRUD/archive; categories, manufacturers, dosage forms, active ingredients, species; withdrawal periods; thresholds; prices/VAT; product search; substitutes by ingredient.
- **Database work:** product and reference tables, indexes and search strategy per OD-018; unique SKU/barcode constraints.
- **Backend work:** `catalog` module endpoints (`api.md` §4.3), price-change auditing.
- **Frontend work:** product list/detail pages, reference-data screens, search UX.
- **Tests:** BR-PROD-*; search performance dataset (NFR-002).
- **Completion criteria:** FR-PROD-001..005, 008, 009 done; search < 1 s on the reference dataset; price changes audited.

## Phase 3 — Lots & units
- **Objective:** sell/store in multiple units and trace by lot.
- **Prerequisites:** Phase 2; OD-009 (expiry boundary), OD-019 (barcode per unit), OD-025 (code collation), OD-027 (lot status semantics).
- **Features:** packaging units and conversion; barcodes by unit; lot entity, statuses, block/unblock/recall (without buyers list until sales exist), expiry alert levels, FEFO allocator (domain service), lot list/detail.
- **Database work:** product_units, barcodes, lots, status constraints, generated expiry-related columns where retained, triggers per ADR-017.
- **Backend work:** `inventory` lot sub-package; `FefoAllocator`; unit conversion service; expiry threshold provider.
- **Frontend work:** units/barcodes tabs, lot list/detail, expiry badges, dashboard alert widget.
- **Tests:** BR-UNIT-*, BR-LOT-*; FEFO unit tests; state-machine tests.
- **Completion criteria:** FR-PROD-006, 007; FR-LOT-004, 005, 006 (rule), 007 (status part), 008 done; lots cannot be created outside controlled flows.

## Phase 4 — Inventory & stock
- **Objective:** a trustworthy, immutable stock ledger.
- **Prerequisites:** Phase 3; OD-012 (count concurrency/scope), OD-013 (reorder algorithm), ADR-012, ADR-028.
- **Features:** ledger; stock levels (sellable vs total); manual adjustments; opening stock; physical inventory (full/cycle); reorder proposals; integrity check.
- **Database work:** stock_movements (append-only), inventory_counts and lines, reorder proposals, CHECK constraints, triggers (two MySQL triggers, ADR-017), balance maintenance.
- **Backend work:** `StockService` (the only writer), locking convention (ascending lot id), count workflow, reorder planner job, integrity check.
- **Frontend work:** stock levels, ledger, adjustment form, count grid and regularisation, reorder list.
- **Tests:** BR-STOCK-*, BR-INV-*; concurrency CT-01/02/04; fault-injection atomicity.
- **Completion criteria:** FR-STOCK-001..007 done (conversion of proposals completes in Phase 5); negative stock impossible even via SQL; ledger equals balances in all tests.

## Phase 5 — Purchasing
- **Objective:** buy goods and bring them into stock with lot and expiry.
- **Prerequisites:** Phase 4; OD-007 (rounding/VAT), OD-014 (non-conforming goods), OD-015 (over-receipt), OD-017 (numbering), OD-029 (lot cost).
- **Features:** suppliers and price history; purchase orders (draft → validated → sent → received/closed); goods receipts; supplier returns; reorder proposal → PO; `NumberingService`.
- **Database work:** suppliers, supplier prices, purchase orders/lines, goods receipts/lines, supplier returns, document sequences (numbering key fix, SD register).
- **Backend work:** `purchasing`, `numbering`; atomic receipt use case; totals computation; PO PDF deferred to Phase 10.
- **Frontend work:** supplier pages, PO editor, goods-receipt screen, supplier return.
- **Tests:** BR-PURCH-*, BR-DOC-001, CT-03, CT-08; e2e J3.
- **Completion criteria:** FR-PURCH-001..007 done; a receipt is all-or-nothing; numbering unique under concurrency.

## Phase 6 — Customers
- **Objective:** know the customers and their commercial conditions.
- **Prerequisites:** Phase 2; OD-006 (price precedence), OD-011 (credit-limit semantics).
- **Features:** customers (types, farming, location, veterinarian link); customer categories and discounts; per-customer prices; credit-limit data; deactivation.
- **Database work:** customers, customer_categories, customer_product_prices, constraints.
- **Backend work:** `sales` customer sub-package; `PriceResolver` domain service (used by Phase 7).
- **Frontend work:** customer list/detail, price and category screens.
- **Tests:** BR-CUST-*, price-resolution unit tests.
- **Completion criteria:** FR-SALE-004, 005, 010 done; credit limit editable only with its permission and audited.

## Phase 7 — Sales / POS
- **Objective:** a fast, correct counter sale and document chain.
- **Prerequisites:** Phases 3–6; OD-002, OD-003, OD-004, OD-010 decided; DR-003 option chosen.
- **Features:** quote, delivery note, invoice; conversions; POS screen with scan and FEFO lot proposal; discounts; withdrawal warnings (in-app); credit sale (uses `CreditLimitPort`); idempotent confirmation; cancellation per OD-004; **minimal counter-payment/cash-session slice** (DR-003 option a).
- **Database work:** sales documents/lines (cost snapshot, lot per line), idempotency store, cash session minimal tables if not yet created.
- **Backend work:** `sales` use cases; stock consumption through `StockService`; recall buyers report completed; confirm transaction (`architecture.md` §5).
- **Frontend work:** POS layout and flows, documents list/detail, convert/cancel actions.
- **Tests:** BR-SALE-*, BR-LOT-003..008 end-to-end; CT-01, 02, 05, 06; e2e J4, J5, J9.
- **Completion criteria:** FR-SALE-001, 002, 003 (screen part), 008, 009; FR-LOT-002, 006 done; stock never negative; confirmed documents immutable; a sale < 2 s.

## Phase 8 — Returns
- **Objective:** handle returns and credit notes correctly.
- **Prerequisites:** Phase 7; OD-005.
- **Features:** customer return wizard; restock vs discard; automatic credit note; refund/settlement per OD-005; supplier-return completion with financial effect (OD-016 in Phase 9).
- **Database work:** customer_returns/lines, credit notes (as sales documents), movement types for discard (SD register).
- **Backend work:** return use case with cumulative limit; credit-note numbering.
- **Frontend work:** return wizard, return lists.
- **Tests:** BR-RET-*; e2e J6.
- **Completion criteria:** FR-SALE-006, 007 done; returns cannot exceed sold quantity; atomic.

## Phase 9 — Payments & finance
- **Objective:** know who owes what, and what is in the till.
- **Prerequisites:** Phases 7–8; OD-003, OD-011, OD-016.
- **Features:** customer payments with allocations and reversal; balance and statement; credit-limit enforcement; supplier invoices, payments, advances, credit notes; cash sessions, movements, variance.
- **Database work:** payments, allocations, supplier invoices, cash sessions/movements, corrected balance view/query (DR-006), unique open-session constraint (ADR-015).
- **Backend work:** `finance` module implementing `CreditLimitPort`, `BalanceQuery`; reversal; session close.
- **Frontend work:** payment dialog, statements, cash screens, supplier payables.
- **Tests:** BR-PAY-*, BR-CASH-*; CT-06, CT-09; e2e J7, J10.
- **Completion criteria:** FR-FIN-001..008 done; balance equals definition in OD-003 on fixtures; payments never edited or deleted.

## Phase 10 — Documents & printing
- **Objective:** produce the printable documents.
- **Prerequisites:** Phase 7–9; OD-020 (printing), OD-026 (company identity), OD-028 (withdrawal-warning applicability).
- **Features:** A4 invoices/quotes/delivery notes/credit notes/PO, thermal tickets, statements; withdrawal-period warnings printed; company identity on documents.
- **Database work:** settings for company identity; none else.
- **Backend work:** `printing` module, templates, generation after commit.
- **Frontend work:** PDF buttons, print flows.
- **Tests:** document content snapshot tests; withdrawal rule coverage.
- **Completion criteria:** NFR-003 met on target printers (manual check); FR-SALE-003, 008 fully done.

## Phase 11 — Reports & audit
- **Objective:** management information and data safety.
- **Prerequisites:** Phase 9; OD-021 (backup), OD-002/003 (scope of margin).
- **Features:** revenue, margin, valuation, sales statistics, balances, payables, cash variance, lot traceability; CSV/PDF export; backups and backup log; audit viewer finalised.
- **Database work:** rewritten MySQL views (ADR-014) with corrected margin/credit-note handling (DR-007), indexes for reports.
- **Backend work:** `reporting`, `backup`.
- **Frontend work:** report pages, export buttons, backups page.
- **Tests:** BR-RPT-*, BR-BKP-*; fixture parity tests.
- **Completion criteria:** FR-RPT-001..003, FR-AUD-002 done; backup and restore rehearsed.

## Phase 12 — Testing, security hardening, deployment
- **Objective:** release readiness.
- **Prerequisites:** Phases 0–11; OD-021; session-security hardening review (ADR-036).
- **Features:** performance tuning at target volume; security review (OWASP ASVS-oriented checklist); accessibility audit; data-migration rehearsal from the legacy source (if any); operations runbook; UAT.
- **Database work:** index tuning; least-privilege DB accounts (BR-SEC-006); restore rehearsal.
- **Backend work:** rate limits, headers, config hardening, observability checks.
- **Frontend work:** accessibility fixes, bundle size, browser matrix.
- **Tests:** full regression, e2e suite, load test, dependency/secret scans, backup restore test.
- **Completion criteria:** all NFRs verified; no open Critical/High items in `documentation-review.md`; user acceptance signed off.

---

## 3. Requirement → phase matrix

Every requirement of `requirements.md` appears below with the phase in which it is *completed* (earlier phases may deliver parts).

| Requirement | Phase | Requirement | Phase |
|---|---|---|---|
| FR-PROD-001 | 2 | FR-SALE-001 | 7 |
| FR-PROD-002 | 2 | FR-SALE-002 | 7 |
| FR-PROD-003 | 3 (SKU in 2) | FR-SALE-003 | 10 (documents in 7) |
| FR-PROD-004 | 2 | FR-SALE-004 | 6 |
| FR-PROD-005 | 2 (warnings 7, print 10) | FR-SALE-005 | 6 (applied 7) |
| FR-PROD-006 | 3 | FR-SALE-006 | 8 |
| FR-PROD-007 | 3 (applied 7) | FR-SALE-007 | 8 |
| FR-PROD-008 | 2 | FR-SALE-008 | 10 (in-app 7) |
| FR-PROD-009 | 2 | FR-SALE-009 | 7 (credit note 8) |
| FR-LOT-001 | 5 | FR-SALE-010 | 6 |
| FR-LOT-002 | 7 | FR-FIN-001 | 9 (minimal 7) |
| FR-LOT-003 | 5 | FR-FIN-002 | 9 |
| FR-LOT-004 | 3 (used 7) | FR-FIN-003 | 9 |
| FR-LOT-005 | 3 | FR-FIN-004 | 9 |
| FR-LOT-006 | 7 | FR-FIN-005 | 9 |
| FR-LOT-007 | 7 (status 3) | FR-FIN-006 | 9 |
| FR-LOT-008 | 3 | FR-FIN-007 | 9 (minimal 7) |
| FR-STOCK-001 | 4 | FR-FIN-008 | 9 |
| FR-STOCK-002 | 4 | FR-SEC-001 | 1 |
| FR-STOCK-003 | 4 | FR-SEC-002 | 1 |
| FR-STOCK-004 | 4 | FR-SEC-003 | 1 |
| FR-STOCK-005 | 4 | FR-AUD-001 | 1 (extended every phase) |
| FR-STOCK-006 | 4 | FR-AUD-002 | 11 |
| FR-STOCK-007 | 4 | FR-RPT-001 | 11 |
| FR-PURCH-001 | 5 | FR-RPT-002 | 11 |
| FR-PURCH-002 | 5 | FR-RPT-003 | 11 |
| FR-PURCH-003 | 5 | NFR-001 | 0–12 (verified 4, 5, 7, 9) |
| FR-PURCH-004 | 5 | NFR-002 | 2, 7, 12 |
| FR-PURCH-005 | 5 | NFR-003 | 10 |
| FR-PURCH-006 | 5 | NFR-004 | 12 |
| FR-PURCH-007 | 5 | NFR-005 | 1 |
| | | NFR-006 | 1 (all) |
| | | NFR-007 | all phases |
| | | NFR-008 | 0 (all migrations) |

## 4. Risks to the plan

1. Undecided OD-002/003 delay Phase 7 (largest risk). 2. Printer variability (Phase 10). 3. Large volumes affecting search (Phase 2/12). 4. Schema deltas discovered late — mitigated by `database-migration.md` and small migrations.
