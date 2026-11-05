# Implementation Roadmap — IMS Vétérinaire

> **Status:** Phases 0–10 complete (tags `phase-0-done` … `phase-10-done`); Phase 11 (Reports & audit) current: decisions ADR-144…147 (2026-11-04), ADR-148, ADR-149 (2026-11-05); Phases 12–13 not started
> **Scope:** ordered plan for Claude Code to implement.
> **Last updated:** 2026-11-02

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
| 2 | Catalogue | products, reference data, search | ~~OD-018~~ ADR-047/048, ~~OD-022~~ ADR-049/050, ADR-051–054 (2026-10-21) |
| 3 | Lots & units | packaging units, barcodes, lots, status, expiry rules, FEFO allocator | ~~OD-009, OD-019, OD-025, OD-027~~ → ADR-055…068 |
| 4 | Inventory & stock | ledger, stock levels, adjustments, opening stock, counts, reorder proposals | ~~OD-012~~, ~~OD-013~~ → ADR-072…079 |
| 5 | Purchasing | suppliers, purchase orders, goods receipts, supplier returns, numbering | ~~OD-007~~, ~~OD-014~~, ~~OD-015~~, ~~OD-017~~, ~~OD-029~~ → ADR-093…105 |
| 6 | Customers | customers, categories, prices, credit-limit data | ~~OD-006~~ → ADR-107, ~~OD-011~~ → ADR-108 |
| 7 | Sales / POS | quotes, delivery notes, invoices, counter sale, FEFO selling | ~~OD-002, OD-003, OD-004, OD-010~~ → ADR-117…120, ADR-115/116/121…126 |
| 8 | Returns | customer returns, credit notes, restock/discard, refunds | ADR-127…131 (was OD-005) |
| 9 | Payments & finance | payments, allocations, balances, statements, cash sessions, supplier invoices | ~~OD-003~~ → ADR-118, ~~OD-016~~ → ADR-132…134; ADR-135…137 (ADR-108: payments enter the credit balance) |
| 10 | Documents & printing | PDFs, tickets, withdrawal warnings on documents | ADR-138…143 (were OD-020, OD-026, OD-028) |
| 11 | Reports & audit | reports, exports, backup | ADR-144…149 (was OD-021, backup part; DR-007) |
| 12 | Hardening & deployment | performance, security review, UAT, deployment procedure | OD-021 (hosting, availability), ADR-036 hardening |
| 13 | Dark theme `[REC]` | user-selectable theme (system / light / dark), contrast verified | ADR-069 (decided 2026-10-22) |

**Dependency adjustment (DR-003).** The POS in Phase 7 needs cash/anonymous-sale payment capture (OD-003) and a cash session (BR-SALE-013, BR-PAY-006), which belong to Phase 9. **Decided 2026-10-31 (ADR-118): option (a)**, a minimal counter-payment and cash-session slice inside Phase 7; manual cash movements and the session report stay in Phase 9.

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
- **Delivered (2026-10-21):** PRs #4–#11 (slices auth-schema, audit-recorder, auth-login, settings, users-admin, frontend-auth, frontend-admin, 24-hour clock); decisions ADR-036…046. All four completion criteria pass (`DenyByDefaultTest`, bcrypt cost 12 tests, `AuditLogAppendOnlyTest`, `EndpointPermissionMatrixTest` with 21 endpoints × 4 roles); BR-SEC-001…005 and BR-AUDIT-001…003 tested; e2e J1 and J11 against the real backend. Deferred: BR-SEC-006 database grants (Phase 12); a concurrency test of the lock-out guard (would commit shared test data); MapStruct (hand-written mappers, ADR-019 note).

## Phase 2 — Catalogue
- **Objective:** maintain the product master data and find products quickly.
- **Prerequisites:** Phase 1; decided 2026-10-21: ADR-047 (target species), ADR-048 (search), ADR-049 (price precision), ADR-050 (divisible), ADR-051 (SKU collation), ADR-052 (seed), ADR-053 (reference delete), ADR-054 (labels of seeded values).
- **Features:** products CRUD/archive; categories, manufacturers, dosage forms, active ingredients, species; withdrawal periods; thresholds; prices/VAT; product search; substitutes by ingredient.
- **Database work:** product and reference tables, target species, indexes and search per ADR-048; unique SKU. Units and barcodes are Phase 3; stock columns and filters Phase 4 (scope confirmed by the user, 2026-10-21).
- **Backend work:** `catalog` module endpoints (`api.md` §4.3), price-change auditing.
- **Frontend work:** product list/detail pages, reference-data screens, search UX.
- **Tests:** BR-PROD-*; search performance dataset of 50,000 products (NFR-002, `testing.md` §8.1).
- **Completion criteria:** FR-PROD-001..005, 008, 009 done; search < 1 s on the reference dataset; price changes audited.
- **Delivered (2026-10-21):** PRs #13–#19 (decisions, then slices catalog-reference, product-core, product-prices, ingredients-substitutes, species-withdrawal, product-search); ADR-047…054; migrations V02_001…006. Search on the 50,000-product dataset: 71–153 ms per search in CI. Within the agreed scope split, FR-PROD-003 barcodes and FR-PROD-006/007 units are Phase 3; stock columns, the in-stock filter and stock-aware substitutes are Phase 4; withdrawal warnings on documents Phases 7/10; refusal of archived products on documents from Phase 5.

## Phase 3 — Lots & units
- **Objective:** sell/store in multiple units and trace by lot.
- **Prerequisites:** Phase 2; decided 2026-10-22: ADR-055/056 (expiry boundary and alert levels, was OD-009), ADR-057 (barcode per unit, was OD-019), ADR-058 (collation, was OD-025), ADR-059/060 (lot status, was OD-027), ADR-061…068.
- **Features:** packaging units and conversion; barcodes by unit; lot entity, statuses, block/unblock/recall (without buyers list until sales exist), expiry alert levels, FEFO allocator (domain service), lot list/detail. No lot creation (ADR-064): lots appear with opening stock (Phase 4) and receipts (Phase 5).
- **Not in this phase:** stock movements and ledger, lot movement history (Phase 4); receipts and `lots.supplier_id` (Phase 5); recall buyers list and FEFO override (Phase 7); BR-UNIT-004 can only be exercised from Phase 5.
- **Database work:** product_units, barcodes (`unit_id`, `utf8mb4_bin`), lots (stored status `active`/`blocked`/`recalled`, `UQ(id, product_id)`, no `supplier_id` until Phase 5); no generated expiry columns and no triggers (expired/depleted computed, ADR-059).
- **Backend work:** `inventory` lot sub-package; `FefoAllocator`; unit conversion service; expiry threshold provider.
- **Frontend work:** units/barcodes tabs, lot list/detail, expiry badges, dashboard alert widget.
- **Tests:** BR-UNIT-*, BR-LOT-*; FEFO unit tests; state-machine tests.
- **Completion criteria:** FR-PROD-006, 007; FR-LOT-004, 005, 006 (rule), 007 (status part), 008 done; lots cannot be created outside controlled flows.
- **Delivered (2026-10-22; approved and tagged `phase-3-done` on `626f614`):** PRs #27 (ADR-070 lot identity) and #28–#33 plus the close PR (slices catalog-units, catalog-barcodes, inventory-lots, inventory-fefo-alerts, inventory-lot-actions, frontend-lots, close); migrations V03_001…003; ADR-070; OD-033 opened (scan of a code containing `/`, blocks Phase 7). J2 extended with units, barcodes, scanning and the empty lot screens. Within the agreed scope split, the conversion and expiry rules are applied to documents from Phases 5/7, the recall buyers list and FEFO override come in Phase 7, and the factor lock (BR-UNIT-004) can be exercised from Phase 5. Expiry and status badges reuse the contrast-checked `ok` / `warn` / `danger` / `brand` tones, so no separate semantic tokens were needed (Phase 0 deferral closed).

## Phase 4 — Inventory & stock
- **Objective:** a trustworthy, immutable stock ledger.
- **Prerequisites:** Phase 3; decided 2026-10-22: ADR-072…075 (counts, was OD-012), ADR-076…079 (reorder, was OD-013), ADR-012 (accepted, amended), ADR-028, ADR-080…089; during the phase ADR-090 (stock conditions across modules) and ADR-091 (statistics after bulk loads).
- **Features:** ledger; stock levels (sellable vs total, status, ADR-080); manual adjustments (ADR-085); opening stock while the opening period is open (ADR-082…084); physical inventory (full/cycle, watermark per counted line, found lots, ADR-072…075); reorder proposals (generate, dismiss; conversion in Phase 5, ADR-076…079); integrity check with stored runs and banner (ADR-086); separate database accounts (ADR-087); stock columns, in-stock filter, stock-aware substitutes, low-stock widget and the blocking user's name (ADR-089).
- **Database work:** stock_movements (append-only), inventory_counts and lines, reorder proposals, CHECK constraints, triggers (two MySQL triggers, ADR-017), balance maintenance.
- **Backend work:** `StockService` (the only writer), locking convention (ascending lot id), count workflow, reorder planner job, integrity check.
- **Frontend work:** stock levels, ledger, adjustment form, count grid and regularisation, reorder list.
- **Tests:** BR-STOCK-*, BR-INV-*; concurrency CT-01/02/04; fault-injection atomicity.
- **Completion criteria:** FR-STOCK-001..007 done (conversion of proposals completes in Phase 5); negative stock impossible even via SQL; ledger equals balances in all tests.
- **Delivered (2026-10-23; approved and tagged `phase-4-done` on `d046a18`):** decisions PR #38 (ADR-072…089), then PRs #39–#47 and #49–#52 (slices database accounts, user names, stock ledger, adjustments, opening stock, stock levels and in-stock search, integrity check, counts, regularisation, reorder proposals, frontend stock, frontend counts and reorder), #48 (ADR-091), #53 (zod advisory), #54 (thresholds inconsistent, ADR-077 amendment) and the close PR; migrations V04_001…009; ADR-090, ADR-091; OD-034 opened at the close and resolved the same day by ADR-092 (warning on the product form). e2e J8 (opening stock → count → regularisation → ledger) added; J2 checks the stock of a new product.
- **Deferred, so nothing is lost:** Phase 5 — convert a proposal to a purchase order (BR-PURCH-010), `supplier_id` on proposals and open-order subtraction (ADR-079), receipts creating lots through `LotIdentities` with `purchase_receipt` movements, unit cost of a repeated lot (OD-029; opening stock refuses another cost with `COST_MISMATCH` until then), `lots.supplier_id`, BR-UNIT-004; Phase 7 — `sale` movements, CT-01 and CT-04 re-run with real sales, recall buyers list (ADR-068), FEFO override (OD-010), cancellation of confirmed documents (OD-004, DR-002); Phase 8 — `customer_return` / `supplier_return` movements and the discard of returned goods (OD-005, DR-002); Phase 11 — reporting views (stock valuation, DR-006/007); Phase 12 — the read-only `backup` database account (ADR-087) and backup design (OD-021); an import tool may run `ANALYZE TABLE` itself (ADR-091 alternative); the production bundle passed 500 kB (code splitting planned in Phase 12).

## Phase 5 — Purchasing
- **Objective:** buy goods and bring them into stock with lot and expiry.
- **Prerequisites:** Phase 4; decided 2026-10-24: ADR-093 (rounding, was OD-007), ADR-094 (PO prices), ADR-095 (non-conforming, was OD-014), ADR-096 (over-receipt and closing, was OD-015), ADR-097 (numbering, was OD-017), ADR-098 (lot cost, was OD-029), ADR-099…105.
- **Features:** suppliers and price history; purchase orders (draft → validated → sent → partially received → closed, forced close, cancel); goods receipts with lots, weighted-average cost, non-conforming lots blocked and idempotent confirmation; cancelling a whole receipt (`receipt.cancel`); supplier returns (stock only); reorder proposals → draft POs with open orders subtracted; `NumberingService`; opening stock adopts the weighted-average cost.
- **Database work:** suppliers, supplier prices, purchase orders/lines, goods receipts/lines, supplier returns, document sequences (numbering key fix, SD register).
- **Backend work:** `purchasing`, `numbering`; atomic receipt use case; totals computation; PO PDF deferred to Phase 10.
- **Frontend work:** supplier pages, PO editor, goods-receipt screen, supplier return.
- **Tests:** BR-PURCH-*, BR-DOC-001, CT-03, CT-08; e2e J3.
- **Completion criteria:** FR-PURCH-001..007 done; a receipt and its cancellation are all-or-nothing; numbering unique under concurrency. The close report repeats that the legal rounding rule for sales invoices must be confirmed with an accountant before Phase 7 (ADR-093).
- **Delivered (2026-10-24; approved and tagged `phase-5-done` on `0c3ff3f`):** decisions PRs #57 (ADR-093…105) and #58 (ADR-106), then PRs #59–#69 (slices suppliers, numbering, purchase orders, idempotency keys, goods receipts, receipt cancellation, the lot-lock reload fix #65, supplier returns, reorder conversion, frontend suppliers and orders, frontend receipts and returns) and the close PR; migrations V05_001…016; SD-126…132; no OD opened. e2e J3 added (runs with J8 after the journeys that expect no lot, known debt for Phase 12). Not delivered here, by plan: supplier invoices, payments and payables on the supplier page (Phase 9, OD-016); the supplier credit note of a return (Phase 9, OD-016); the purchase-order PDF (Phase 10). The close report repeats that an accountant must confirm the legal rounding rule for sales invoices (per line or per VAT rate) before Phase 7 (ADR-093).

## Phase 6 — Customers
- **Objective:** know the customers and their commercial conditions.
- **Prerequisites:** Phase 2; ~~OD-006~~ → ADR-107 (price precedence), ~~OD-011~~ → ADR-108 (credit-limit semantics: Phase 6 stores the nullable limit and audits changes; the check comes with sales); ADR-109…114 (customer record, duplicates, customer prices, categories, discount entitlement, personal data; 2026-10-25).
- **Features:** customers (types, farming, location, veterinarian link); customer categories and discounts; per-customer prices; credit-limit data; deactivation.
- **Database work:** customers, customer_categories, customer_product_prices, constraints.
- **Backend work:** `sales` customer sub-package; `PriceResolver` domain service (used by Phase 7).
- **Frontend work:** customer list/detail, price and category screens.
- **Tests:** BR-CUST-*, price-resolution unit tests.
- **Completion criteria:** FR-SALE-004, 005, 010 done; credit limit editable only with its permission and audited.
- **Delivered (2026-10-25; approved and tagged `phase-6-done` on `224af14`):** decisions PRs #71 (ADR-107, ADR-108) and #73 (ADR-109…114, SD-134), then PRs #74–#80 (slices customer categories, customers with duplicate detection and the veterinarian link, credit limit under the row lock, customer prices, the pure `PriceResolver` and the `CustomerPricing` port, the customers frontend with the three-state credit-limit dialog, demo customers) and the close PR; migrations V06_001…003; no OD opened. e2e J12 added (own `customers` project, after `stock`). `Phase6UpgradeMigrationTest`: Phase 5 data upgraded to V06_003, balances still equal the ledger. Not delivered here, by plan: the balance, statement, invoices and payments of a customer (Phases 7 and 9, OD-003); the credit-limit check at sale time (Phase 7, `CreditLimitPort`, ADR-108); BR-CUST-004 (sale to an inactive customer, Phase 7). Before Phase 7: an accountant must confirm the legal rounding rule for sales invoices (per line or per VAT rate, ADR-093), and whether credit sales are enabled before Phase 9 is settled (ADR-108).

## Phase 7 — Sales / POS
- **Objective:** a fast, correct counter sale and document chain.
- **Prerequisites:** Phases 3–6; ~~OD-002, OD-003, OD-004, OD-010~~ → ADR-117…120; ADR-115, ADR-116, ADR-121…126 (2026-10-31); DR-003 option (a).
- **Credit sales (ADR-116):** enabled from Phase 7 with the limit enforced; until Phase 9 a balance only grows, so demo and test customers will hit their limit and raising it is the workaround.
- **Slice order (user rule, 2026-10-31):** confirmation first handles immediate sales only; a credit sale is refused with a clear error until the next slice adds `CreditLimitPort`, so no credit sale is ever confirmed without the limit check.
- **Features:** quote, delivery note, invoice; conversions; POS screen with scan and FEFO lot proposal; discounts; withdrawal warnings (in-app); credit sale (uses `CreditLimitPort`); idempotent confirmation; restricted cancellation (ADR-119); **minimal counter-payment/cash-session slice** (DR-003 option a).
- **Database work:** sales documents/lines (cost snapshot, lot per line), idempotency store, cash session minimal tables if not yet created.
- **Backend work:** `sales` use cases; stock consumption through `StockService`; recall buyers report completed; confirm transaction (`architecture.md` §5).
- **Frontend work:** POS layout and flows, documents list/detail, convert/cancel actions.
- **Tests:** BR-SALE-*, BR-LOT-003..008 end-to-end; CT-01, 02, 05, 06; e2e J4, J5, J9.
- **Completion criteria:** FR-SALE-001, 002, 003 (screen part), 008, 009; FR-LOT-002, 006 done; stock never negative; confirmed documents immutable; a sale < 2 s; concurrency tests CT-01 and CT-04 re-run with real sales (in Phase 4 they use a manual adjustment in place of a sale, user decision 2026-10-22).
- **Delivered (2026-10-31; approved and tagged `phase-7-done`):** decisions PR #83, then PRs #84–#94 (slices drafts, lines and warnings, cash sessions, counter payments, confirmation, credit, conversions, cancellation, recall buyers, POS, documents) and the close PR; migrations V07_001…012. e2e J4, J5, J9 and the sales part of J11 (own `sales` project). `Phase7UpgradeMigrationTest`: Phase 6 data upgraded to V07_012. `SalePerformanceTest`: a whole credit sale of 5 lines, server side, 130 ms median and 337 ms slowest of 20 (measured 2026-10-31). **Go-live blockers:** DR-040 (legal rounding, ADR-115) and DR-041 (prescription-only products, ADR-124). Not delivered here, by plan: printing and PDF (Phase 10, OD-020; FR-SALE-003 is the screen part, FR-SALE-008 on screen); returns and credit notes, and the return check on cancellation and the recall quantities (Phase 8); account payments, manual cash movements, the session report (Phase 9). Known: the documents derived from one are not listed on its page; a supplier choice made right after the new-order page opens can be lost (J3 retries it, cause not found); the accessibility target of the POS is not audited, only its scan flow by keyboard.

## Phase 8 — Returns
- **Objective:** handle returns and credit notes correctly.
- **Prerequisites:** Phase 7; decided 2026-11-02: ADR-127 (discard ledger), ADR-128 (refunds), ADR-129 (dispositions, product flag), ADR-130 (quantity invariant, credit-note amounts), ADR-131 (what can be returned, immutability); was OD-005. OD-040 (correcting a confirmed return) is registered and **not built** in Phase 8.
- **Features:** customer return wizard with a server preview; restock (back into the original lot, a blocked or recalled lot staying unsellable) vs discard with a reason; automatic credit note; refund in full of immediate sales (cash, cheque, transfer) or credit to the account of a credit sale; the return check on cancellation; returned and net quantities in the recall buyers list; the product flag `return_restock_forbidden`; the setting `customer_return_max_days`. Not here: store credit and advances (Phase 9, OD-016), card payments (no ADR), supplier-return financial effect (Phase 9, OD-016), printing of credit notes (Phase 10).
- **Database work:** `customer_returns`/lines, `return_discard` movement type, `sales_document_lines.returned_quantity_base` and credited amounts, `customer_refunds` and `cash_movements.customer_refund_id`, `products.return_restock_forbidden`, the setting, numbering key `CR` (SD-107, SD-139…142).
- **Backend work:** return use case (lock order of ADR-130, idempotent, atomic) and preview; refunds through a `finance` port; cancellation guard; recall net quantities; return list and detail.
- **Frontend work:** return wizard, return list and detail, sale detail with returns, product flag.
- **Tests:** BR-RET-001…011, BR-PROD-009, concurrency (parallel returns; return vs cancellation), fault injection, `Phase8UpgradeMigrationTest`; e2e J6, J11 extended (a Seller cannot reach returns).
- **Completion criteria:** FR-SALE-006, 007 done; returns cannot exceed the sold quantity under concurrency; a return with its credit note, movements and refund is all-or-nothing; a fully returned line is credited exactly what was invoiced. The close report repeats the go-live blockers DR-040 (now including credit notes, ADR-130) and DR-041.
- **Delivered (2026-11-02; approved and tagged `phase-8-done`):** decisions PR #96 (ADR-127…131, SD-139…142, DR-042, OD-040), then PRs #97 (schema and ledger), #98 (refund ledger), #99 (product restriction and age limit), #100 (the customer return), #101 (cancellation guard, recall net quantities, returns on the sale), #102 (list endpoint, wizard, lists, detail, product switch, e2e J6) and the close PR; migrations V08_001…008. `Phase8UpgradeMigrationTest`: Phase 7 data upgraded to V08_008. `ReturnPerformanceTest`: a return of 5 lines with its preview, 82 ms median and 163 ms slowest of 20 (measured 2026-11-02). Demo data gain a counter return (restock, discard, cash refund) and a credit-invoice return; the vaccines are restricted. **Go-live blockers:** DR-040 (legal rounding, now also of credit notes, ADR-115, ADR-130) and DR-041 (prescription-only products, ADR-124). Not delivered here, by plan: OD-040 (correcting a confirmed return, registered, open); store credit and advances (Phase 9, OD-016); printing of credit notes (Phase 10). Known: the wizard enters quantities in base units only (the API also takes the sold unit).

## Phase 9 — Payments & finance
- **Objective:** know who owes what, and what is in the till.
- **Prerequisites:** Phases 7–8; ADR-118, ADR-108 (payments enter the credit balance); decided 2026-11-03: ADR-132 (supplier allocations and advances), ADR-133 (supplier invoices and credit notes, cancellation), ADR-134 (customer payments on account, advances, statement), ADR-135 (reversal), ADR-136 (cash), ADR-137 (scope); was OD-016. OD-041 (correcting a mistaken allocation) is registered and **not built** in Phase 9.
- **Features:** customer payments on account with allocations (oldest first by default) and advances applied later; balance and statement on screen; payment reversal (customers and suppliers); supplier invoices, credit notes (optional link to a supplier return) and their application, payments with allocations and advances, payables by due date, cancellation of a wrong entry; manual cash movements, closing another user's session, the session report on screen.
- **Database work:** `supplier_invoices`, `supplier_payments`, `supplier_payment_allocations`, `supplier_credit_allocations` (append-only except the invoice's cancellation); surrogate id on `payment_allocations` (SD-145); `cash_movements` sources (SD-146); no balance or payables view (DR-006: service query).
- **Backend work:** `finance` module: payments on account, allocation and later application under locks (payment, then invoices ascending), balance query shared by `CreditLimitPort` and the statement, reversal, supplier invoices and payments, manual movements, session close by a manager and the report.
- **Frontend work:** payment dialog with the proposed split, customer statement, supplier invoices and payables, supplier payment dialog, cash screens (movements, report, sessions list).
- **Tests:** BR-PAY-*, BR-CASH-*; CT-06, CT-09, CT-11, CT-12; balance parity (DR-006); `Phase9UpgradeMigrationTest`; e2e J7, J10.
- **Completion criteria:** FR-FIN-001..008 done; balance equals the definition in ADR-118 on fixtures; payments and allocations never edited or deleted.
- **Not in Phase 9 (ADR-137):** store credit on returns of cash sales; PDFs of the statement and the session report (Phase 10, OD-020); moving an allocation (OD-041).
- **Delivered (2026-11-04; approved and tagged `phase-9-done`):** decisions PR #105 (ADR-132…137, SD-143…146, OD-016 resolved, OD-041), then PRs #106 (customer payments on account, allocations, advances), #107 (statement and balance parity), #108 (customer payment reversal), #109 (supplier invoices and credit notes, applications, cancellation), #110 (supplier payments, advances, reversal, payables), #111 (manual cash movements, session report, closer in the variance audit), #112 (customer Account tab, e2e J7), #113 (supplier invoices, Payables tab, supplier payments, cash screens, e2e J10) and the close PR; migrations V09_001…007. `Phase9UpgradeMigrationTest`: Phase 8 data upgraded to V09_007, existing allocations keep their amounts with a new id, balances unchanged. FR-FIN-001…008 covered; the balance equals ADR-118 on fixtures (parity test, DR-006); payments, allocations, applications and cash movements are append-only (triggers and grants), corrected by reversal. During the phase the user decided: cancelling a credit invoice that an account payment settles is refused (ADR-119 amended, `blockedBy: payment`), and payments lock the customer or supplier row rather than the payment row (append-only tables have no UPDATE grant, so no `FOR UPDATE`). Demo data gain supplier invoices for the three order receipts (one settled by the credit note of the supplier return and a transfer, one partly paid after a cheque reversed, one overdue and partly paid in cash), an advance to a supplier, a manual cash out and a customer cheque on account. **Go-live blockers** unchanged: DR-040 (legal rounding) and DR-041 (prescription-only products). Not delivered here, by plan: the PDFs (Phase 10), OD-041 (moving a mistaken allocation, open), store credit (ADR-137). Known: the cash-session screens show cashiers by user id (the API carries ids only; names would need a finance → users read port, not decided); there are no all-party payment lists (`/payments/*`), payments being on the customer's Account tab and the supplier's Payables tab.

## Phase 10 — Documents & printing
- **Objective:** produce the printable documents.
- **Prerequisites:** Phases 7–9; decided 2026-11-04: ADR-138 (HTML templates to PDF with openhtmltopdf, bundled fonts, French, rendered on request, nothing stored; was OD-020), ADR-139 (80 mm ticket PDF printed by the browser, print flows; was OD-020), ADR-140 (versioned company profile; was OD-026), ADR-141 (legal mentions, amount in words, customer NIS/RC/AI, no stamp duty; DR-043), ADR-142 (printed withdrawal warnings, the screen rule; was OD-028), ADR-143 (documents in scope, identical reprints, drafts refused, cancelled watermarked).
- **Features:** A4 invoices, quotes, delivery notes, receipts and credit notes, thermal tickets, purchase orders, the customer statement, the cash-session report and the recall buyers list; withdrawal-period warnings printed; company identity on documents; the company profile page; customer NIS/RC/AI.
- **Database work:** `company_profiles` (append-only, SD-147); `customers.nis`, `rc`, `ai` (SD-148).
- **Backend work:** `printing` module (templates, fonts, amount in words, watermark), PDF endpoints, the company profile in `settings`.
- **Frontend work:** PDF buttons, the ticket at the counter, the company profile page, the customer legal numbers.
- **Tests:** extracted-text tests per document type against fixtures; withdrawal rule coverage; amount in words; drafts refused; PDF < 2 s; `Phase10UpgradeMigrationTest`.
- **Completion criteria:** NFR-003 met on target printers (manual check); FR-SALE-003, 008 fully done.
- **Delivered (2026-11-04; approved and tagged `phase-10-done`; the printer check of NFR-003 confirmed by the user):** decisions PR #115 (ADR-138…143, SD-147/148, DR-043; OD-020, OD-026, OD-028 resolved), then PRs #117 (versioned company profile, customer NIS/RC/AI), #118 (sales documents in A4 and the 80 mm ticket), #119 (credit note, purchase order, recall buyers list), #120 (customer statement, cash-session report) and the close PR (a fictional company profile and customer legal numbers in the demo data); migrations V10_001…002. Every document of ADR-143 prints from `printing` (openhtmltopdf, bundled Noto fonts, French); tests `SalePdfsTest`, `DocumentPdfsTest`, `AmountInWordsTest`, `CompanyProfilesTest`, `Phase10UpgradeMigrationTest`; an invoice renders in under 2 s. No e2e journey prints (the PDFs are checked on their text server side; the buttons in unit tests). Known: the cash-session report names users by id, as its screen (`printing` does not read `users`). Go-live blockers unchanged: DR-040 (legal rounding, ADR-115), DR-041 (prescription-only products, ADR-124).

## Phase 11 — Reports & audit
- **Objective:** management information and data safety.
- **Prerequisites:** Phase 9; decided 2026-11-04: ADR-144 (the application takes the backups with `mysqldump` and the read-only `backup` account; restore by a written procedure), ADR-145 (daily, the last 30 kept), ADR-146 (off-site copy by manual download, with a reminder), ADR-147 (revenue and margin at invoice or receipt, net of credit notes; uninvoiced delivery notes apart); was OD-021 (backup part) and DR-007; decided 2026-11-05: ADR-148 (receivables ageing from the due date with the `ageing_buckets` setting, delivery notes apart; payables by due date; cash variances of sessions closed in the period), ADR-149 (exports: CSV for French spreadsheets, PDF tables, not audited).
- **Features:** revenue, margin, valuation, sales statistics, balances, payables, cash variance, lot traceability; CSV/PDF export; backups and backup log; audit viewer finalised.
- **Database work:** rewritten MySQL views (ADR-014) with corrected margin/credit-note handling (DR-007), indexes for reports.
- **Backend work:** `reporting`, `backup`.
- **Frontend work:** report pages, export buttons, backups page.
- **Tests:** BR-RPT-*, BR-BKP-*; fixture parity tests.
- **Completion criteria:** FR-RPT-001..003, FR-AUD-002 done; backup and restore rehearsed.
- **Delivered (2026-11-05; awaiting the user's approval and the tag `phase-11-done`):** decisions PR #122 (ADR-144…147; OD-021 backup part and DR-007 resolved), then PRs #123 (revenue and margin net of credit notes), #124 (stock valuation, sales statistics), #125 (receivables ageing, supplier payables; ADR-148), #126 (cash-session variances, lot traceability), #127 (CSV exports; ADR-149), #128 (report PDFs sharing the export tables), #129 (backups: daily `mysqldump` under a read-only account, Run now, download, retention, warnings, restore procedure), #130 (report pages and backups page) and the close PR; migrations V11_001…010 (views `v_sales_margin`, `v_uninvoiced_delivery_notes`, `v_stock_valuation`, `v_customer_receivables`, `v_supplier_open_items`, `v_cash_sessions`, `v_lot_traceability`, all `SQL SECURITY INVOKER` and read-only for the application; `backup_log`; settings `ageing_buckets`, `backup_time`, `backup_retention_count`, `backup_download_reminder_days`); SD-149…155. FR-RPT-001…003 and FR-AUD-002 covered; the finance reports agree with the Account and Payables tabs on the same data (parity tests); **backup and restore rehearsed** by `BackupRestoreRehearsalTest` (a backup restored into an empty server by the README procedure, Flyway validation, tables, views, triggers and rows equal). `Phase11UpgradeMigrationTest`: Phase 10 data upgraded to V11_010 unchanged, every report view reading it. The audit viewer filters on every audit action (the seven added since Phase 4 were missing). During the phase the user decided ADR-148 (ageing from the due date with `ageing_buckets`, delivery notes apart, cash variances of closed sessions) and ADR-149 (CSV for French spreadsheets, PDF tables, exports not audited), and deferred the report charts to Phase 13 (2026-11-05). **Go-live blockers** unchanged: DR-040 (legal rounding) and DR-041 (prescription-only products). Not delivered here, by plan: hosting, availability, RTO and who operates the server (OD-021, Phase 12); the report charts (Phase 13).

## Phase 12 — Testing, security hardening, deployment
- **Objective:** release readiness.
- **Prerequisites:** Phases 0–11; OD-021; session-security hardening review (ADR-036).
- **Features:** performance tuning at target volume; security review (OWASP ASVS-oriented checklist); accessibility audit; data-migration rehearsal from the legacy source (if any); operations runbook; UAT.
- **Database work:** index tuning; review of the least-privilege DB accounts introduced in Phase 4 (ADR-087, BR-SEC-006); restore rehearsal.
- **Backend work:** rate limits, headers, config hardening, observability checks.
- **Frontend work:** accessibility fixes, bundle size, browser matrix. The production bundle passed 500 kB in Phase 4 (Vite warning): split the large pages (POS, reports) into lazily loaded chunks here (user decision 2026-10-23).
- **Tests:** full regression, e2e suite, load test, dependency/secret scans, backup restore test.
- **Completion criteria:** all NFRs verified; no open Critical/High items in `documentation-review.md`; user acceptance signed off.

## Phase 13 — Dark theme `[REC]`
> Recommendation accepted by the user (ADR-069, 2026-10-22); not a Spec requirement.
- **Objective:** a dark theme the user can choose.
- **Prerequisites:** Phase 12 (or any earlier point the user chooses, since it touches no business rule); ADR-069; the token-only rule followed since Phase 1 (`frontend.md` §6).
- **Features:** the report charts deferred from Phase 11 (user decision 2026-11-05; a simple chart beside the table of a report by date, `frontend.md` §4.10); follows the operating-system setting by default; a theme switch with three choices (system / light / dark) in the application header; the choice is remembered per browser; no flash of the wrong theme while the page loads.
- **Database work:** none. The choice is not stored in the database unless the user later decides it must follow the user across devices (new ADR).
- **Backend work:** none (same condition).
- **Frontend work:** theme switch component (accessible radio group or menu, translated fr/ar/en, RTL-ready); the choice stored in `localStorage` with every access wrapped in `try/catch` so the app works when storage is blocked (it then simply follows the system); `data-theme` set on `<html>` for light/dark and removed for system; a small blocking script in `index.html` applies the stored choice before first paint (allowed by the Content-Security-Policy via its hash, `frontend.md` §14); `color-scheme` set so native controls and scrollbars follow; charts read their colours from tokens. Token contrast was already fixed on 2026-10-22 (ADR-069 amendment); any new token keeps passing `contrast.test.ts`.
- **Tests:** component test of the switch (choices, keyboard, accessible names, persistence across a remount, storage that throws); the existing automated contrast test (`contrast.test.ts`, both themes) stays green; axe run of the main pages in dark (login, products, lots, POS, admin); visual check of POS, tables, badges, alerts and charts in both themes; e2e check that the choice survives a reload with no flash.
- **Completion criteria:** the theme switch works and the choice survives reload without a flash; visual checks done; `contrast.test.ts` still green in both themes; no hard-coded colours in components (automated search in CI).

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

1. Go-live blockers: the accountant's confirmation of invoice rounding (DR-040) and the client's answer on prescription-only products (DR-041). 2. Printer variability (Phase 10). 3. Large volumes affecting search (Phase 2/12). 4. Schema deltas discovered late — mitigated by `database-migration.md` and small migrations.
