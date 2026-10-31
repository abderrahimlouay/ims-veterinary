# Testing Strategy — IMS Vétérinaire

> **Status:** Documentation / Architecture Phase — Implementation Not Started
> **Scope:** what to test, at which level, and when a module is "done". No test code is written in this phase.
> **Last updated:** 2026-10-18

Tags: `SRC` Spec · `REC` recommendation · `OD-nnn` open decision · `BR-…` business rule.

## 1. Principles

1. **Business rules are the test oracle.** Every rule in `business-rules.md` has at least one automated test (matrix §9). A rule without a test is not implemented.
2. **Real database for anything that touches stock, locking, constraints or SQL**: Testcontainers MySQL 8.4 (ADR-030). H2 or in-memory substitutes are forbidden for these tests (MySQL-specific behaviour: CHECK error 3819, locks, generated columns, collation, triggers).
3. **Test the failure path as much as the happy path:** every error code in `api.md` §3 is produced by at least one test.
4. **Atomicity is tested by fault injection:** force a failure mid-use-case and assert nothing persisted.
5. **Open decisions are not guessed in tests.** Tests for behaviour under an `OPEN DECISION` are written only after the decision is recorded; until then they are marked `@Disabled("OD-nnn")` with the reason, and the roadmap phase does not complete with silent gaps.
6. **Deterministic:** fixed `Clock` bean in tests; no sleeps; no dependency on the current date (expiry boundary tests inject the date). The scheduled jobs are off in tests (`ims.jobs.enabled=false`, a surefire system property; set it too when running tests from an IDE): tests call the job ticks directly, so no background tick can commit a run while a test has moved the shared clock (guarded by `IntegrityCheckJobTest`).
7. **Fast feedback:** unit tests < seconds; integration suite parallelisable per module; one MySQL container per Spring test-context configuration, shared by the classes that use that configuration (ADR-035) — reuse the existing configurations instead of adding per-class variations.

## 2. Test pyramid and levels

| Level | Code | Scope | Tools |
|---|---|---|---|
| Unit | **U** | domain logic, value objects, state machines, price/FEFO/conversion/total computation, mappers | JUnit 5, Mockito (only for ports), AssertJ |
| Repository | **R** | queries, locking, constraints, triggers, generated columns, views, indexes | `@DataJpaTest` + Testcontainers MySQL, Flyway applied |
| Service integration | **S** | complete use cases incl. transaction, audit, numbering, rollback | `@SpringBootTest` + Testcontainers |
| API | **A** | HTTP contract: status, body, error code, validation, pagination | MockMvc/RestAssured, JSON schema asserts |
| Security | **SEC** | authentication, permission matrix, headers, brute force | Spring Security Test |
| Concurrency | **C** | parallel stock/numbering/credit operations | executor + latch, repeated runs |
| Migration | **M** | Flyway on empty DB and on previous-release data, schema equals expected | Testcontainers |
| Frontend unit/component | **FU** | components, forms, hooks | Vitest + RTL + MSW |
| End-to-end | **E** | key user journeys through UI + API + DB | Playwright |

## 3. Test data

- Builders/fixtures per aggregate with sensible defaults; one canonical **seed scenario** (products with units and barcodes, lots with varied expiries and statuses, customers of each type, one supplier, open and closed cash sessions).
- Never reuse production data; no personal data in fixtures. Customer name, phone, email, address and tax id never appear in captured logs (ADR-114 test), console output (`CustomerPages.test.tsx` checks every console call), error reports, snapshots or e2e artefacts. Fixtures, demo data and e2e journeys use invented names and phone numbers of the **reserved fake range `0100 00 xx xx`** only (prefix 01 is allocated to no Algerian operator or area; `+213 100 …` once normalised).
- Time-dependent fixtures use relative dates from the test `Clock`.
- Database cleanup by transactional rollback (or a fresh container for a new test-context configuration, ADR-035) — classes sharing a configuration share one database, so tests must not depend on data left by another class; concurrency tests use committed data and explicit cleanup. Rows of the append-only ledgers (`stock_movements`, `audit_log`) can never be deleted, so tests that commit them import `CommittedDataDatabase`, which gives them their own context and MySQL container; the shared database stays clean for the others.
- Lots created by the `TestLots` fixture with stock come with their `opening_stock` movement, so ledger = balance (BR-STOCK-007) holds in every test; `StockAssertions.assertLedgerMatchesBalances` checks it.

## 4. Backend test requirements by layer

**Unit.** FEFO allocator (ordering, ties, partial lots, non-sellable skipped); unit conversion (exact decimals); price resolution (ADR-107: each level and no stacking) and discount bounds; totals with rounding (ADR-093); state machines (lot, PO, sales document, count, cash session, reorder); return limit calculation; balance and expected-cash formulas.

**Repository.** Unique constraints (SKU, barcode, lot per product, one open session per cashier), CHECK constraints (non-negative balances, positive quantities, signed movement rules), trigger behaviour for append-only tables, generated columns, view parity with domain computation, `FOR UPDATE` ordering, collation behaviour for codes (ADR-024, ADR-041, ADR-051, ADR-058: lot numbers insensitive, barcodes exact), contains search and its ordering (ADR-048).

**Service integration.** One test class per workflow in `workflows.md` (W1–W12): happy path, each documented failure case, audit row, movement rows, numbering, and a rollback test.

**API.** For each endpoint: success, validation failure, unauthorised (401), forbidden (403), not found, and each documented business error; response matches `api.md` shape; Problem Details contain `code`, `traceId`; decimals serialised as strings; pagination envelope; unknown sort field rejected.

## 5. Workflow test checklist

| Workflow | Must cover |
|---|---|
| Purchasing (W1) | draft→validated→sent; partial and full receipt; lot create/merge; over-receipt; conformity; inactive supplier; atomic rollback; supplier price history |
| Sales / POS (W2) | FEFO allocation across lots; expired/blocked/recalled refusal; insufficient stock; discount rights; credit limit; idempotent confirm; withdrawal warnings; cash-session link; documents numbering |
| Cancellation (W2b) | ADR-119: allowed and refused cases, reversal, payment reversal, atomicity |
| Customer return (W3) | partial returns, cumulative limit, restock vs discard (OD-005), credit note, refund |
| Supplier return (W4) | lot reduction, insufficient stock, financial effect (OD-016) |
| Inventory count (W5) | open, enter, variance, regularise, concurrent sale, one open full count, closed immutability |
| Recall (W6) | status change, buyers (invoices + delivery notes + anonymous), report, no stock deletion |
| Adjustment (W7) | each category, reason, permission, audit |
| Payment / statement (W8) | allocation rules, reversal, balance, statement running total |
| Cash session (W9) | open/close, variance, one per cashier, manual movements |
| Reorder (W10) | proposal generation, conversion once, dismiss |
| Conversion (W11) | quote→DN→invoice chain, no double stock effect |
| Expiry alerting (W12) | thresholds from settings, level boundaries |

## 6. Concurrency tests (mandatory)

| ID | Scenario | Expected |
|---|---|---|
| CT-01 | N parallel sales of the last unit of a lot | exactly stock/qty succeed; others `STOCK_INSUFFICIENT`; balance ≥ 0; ledger = balance |
| CT-02 | two sales taking lots A,B vs B,A | no deadlock (ascending lock order) or bounded retry succeeds |
| CT-03 | parallel document numbering (1 000 allocations) | unique, gap-free on commit (ADR-097) — `NumberingTransactionTest`, 20 repetitions |
| CT-04 | sale vs count regularisation on same lot | consistent final balance, no lost update; the sale after the watermark stays valid (ADR-072) — `CountRegularisationConcurrencyTest`, 20 repetitions |
| CT-04a | a lot read earlier in a transaction, then withdrawn from by another transaction, then locked and moved | the withdrawal is not lost: balance and weighted cost from the committed state, ledger = balance (`LotLockReloadTest`; supplier returns overdrawing one lot ×20 in `SupplierReturnConcurrencyTest`); no other way to lock a lot exists (`LotLockingRuleTest`: the other lock helpers — customers, proposals, orders — may not even refer to `Lot` or `LotRepository`; no `@Lock` query returning lots, no `EntityManager` lock call outside the lock helpers, no SQL text locking `lots`) |

Until sales exist (Phase 7), CT-01 and CT-04 use a manual adjustment in place of a sale; Phase 7 re-runs both with real sales (roadmap Phase 7 completion criteria).
| CT-05 | double-submit same Idempotency-Key | one document (`IdempotencyKeysTest`, 20 repetitions: the second waits on the key, then replays the first; per endpoint again in BR-PURCH-013 / BR-SALE-016) |
| CT-06 | two credit sales for same customer near limit | limit not exceeded (ADR-108) |
| CT-07 | simultaneous edit of same product (`@Version`) | second gets `CONCURRENT_MODIFICATION` |
| CT-08 | parallel goods receipts of same new lot number | one lot, summed balance, weighted-average cost, ledger = balance (`GoodsReceiptConcurrencyTest`, 20 repetitions; with two receipts beyond one order line's outstanding quantity: one refused) |
| CT-09 | two cash sessions opened concurrently by same cashier | one succeeds |
| CT-10 | two simultaneous blocks of the same lot | one succeeds, the other `INVALID_STATE_TRANSITION`; one `LOT_BLOCK` audit entry (`LotActionConcurrencyTest`, Phase 3) |

Each runs repeatedly (≥ 20 iterations) in CI to expose flakiness.

## 7. Security tests

| Area | Tests |
|---|---|
| Authentication | all endpoints except login reject missing/invalid/expired credentials (generated from the endpoint inventory in `api.md`); disabled user refused; generic failure message; throttle after repeated failures |
| Authorisation | table-driven endpoint × role matrix from `security.md` §4 (every permission code has an allow and a deny test); object-level checks (own cash session); administrator lock-out refused for deactivation, role change and permission removal, self-deactivation refused (ADR-042) |
| Input | SQL injection payloads in search/sort parameters; oversized payloads; mass-assignment (server-owned fields ignored) |
| Output | no entity/stack/SQL in errors; no password/hash fields in any DTO; headers (HSTS, nosniff, frame) present |
| Secrets | build fails on committed secrets (scanner in CI); logs scanned for passwords/tokens in test |
| Audit | every audited action code produces a row; audit immutability |
| Transport/CORS | disallowed origin rejected; session cookie flags (`HttpOnly`, `Secure`, `SameSite=Strict`); state-changing request without CSRF header → 403 (ADR-036) |
| Dependency | dependency vulnerability scan in CI |

## 8. Frontend and end-to-end tests

- **Component/page (Vitest + RTL + MSW):** forms (validation, server error mapping), tables (sort, empty, loading, error), permission gating, each error `code` UI treatment, POS cart behaviour, decimal handling as strings.
- **Async wait budget:** `findBy`/`waitFor` wait up to 3 s (`src/test/setup.ts`). The first test of a file renders the whole application cold, about 0.5 s on an idle machine and over 1 s under CPU contention, which made the first page test of a file flaky against the library's 1 s default (measured 2026-10-22). Raise this budget only with a new measurement; a slow test is investigated, not given a per-test timeout.
- **Accessibility:** automated axe checks on main pages; keyboard-only POS test; `role="alert"` assertion for dynamic errors.
- **Playwright journeys (against real backend + MySQL):** J1 login/logout; J2 create product with units/barcode; J3 purchase order → receipt → lots visible, then the receipt cancelled (`j3-purchase-receipt.spec.ts`, Phase 5); J4 POS sale with FEFO across two lots; J5 sale refused (expired/insufficient); J6 customer return with credit note; J7 customer payment with allocation; J8 inventory count and regularisation (from Phase 4: opening stock → cycle count → regularisation → lot ledger, `j8-inventory-count.spec.ts`, run after the journeys that expect no lot); J9 recall and buyers list; J10 cash session open/close; J11 permission-restricted user sees forbidden states; J12 customers (Phase 6, `j12-customers.spec.ts`: a category and a product, a customer created through the form, found again by searching the phone in another format, an own price set on the Prices tab, the credit limit from cash only to no limit to a limit), run in its own `customers` project after `stock`, so no earlier journey sees its data.

### 8.1 Search performance dataset (NFR-002, ADR-048)

A performance test generates, inside its own transaction, **50,000 products** (`architecture.md` §14) with realistic names, 2,000 active ingredients (each product linked to 1–3), the seeded categories, dosage forms and species, target species and withdrawal periods; then runs representative searches (a word inside names, an SKU, an ingredient name, a species filter, an archived-included search, a term matching nothing) through the API. Every search must answer in **under 1 s**; the slowest time is reported. If it fails, `FULLTEXT` is considered through a new ADR. Implemented by `ProductSearchPerformanceTest`; first measurement (2026-10-21, local Testcontainers MySQL 8.4): 61–125 ms per search, the species filter being the slowest. From Phase 4 (ADR-090) the dataset adds lots for two products in three (some expired, some blocked) and thresholds on half of them, and the searches include in-stock only, a word with in-stock, and the stock list by `low`, below reorder and word with `in_stock`; the dataset is committed in the committed-data database and `ANALYZE TABLE` refreshes the statistics (ADR-091), because a plan made against an uncommitted bulk load saw an empty `lots` table and scanned it (3.4 billion rows examined). Measured 2026-10-23: slowest typically 396–426 ms, up to 811 ms in one run on a loaded machine; all under 1 s.

- **Known debt (Phase 12): journey order.** J3 and J8 create lots, while J2 expects the lots page to be empty; the Playwright projects therefore run J3 and J8 in the `stock` project, after the `chromium` project (J2 and the others). The journeys depend on each other's data in one shared database; Phase 12 should give each journey its own data (or reset between projects) so that the order no longer matters.

## 9. Business-rule → test matrix (complete)

Levels per §2. "Key scenarios" are the minimum; implementers add more. Rules whose behaviour depends on an open decision are tested after that decision is recorded.

| BR ID | Test levels | Key scenarios (minimum) |
|---|---|---|
| BR-PROD-001 | R,A | Create two products with same SKU (also differing only by case/accent, ADR-051) → 409 PRODUCT_SKU_DUPLICATE |
| BR-PROD-002 | R,A | Add an existing barcode to another product → 409 BARCODE_DUPLICATE; `abc` and `ABC` are distinct QR/CODE128 codes (ADR-058) (`ProductBarcodeSchemaTest`, `ProductBarcodesTest`) |
| BR-PROD-008 | U,A | EAN13 with wrong length / letters / wrong check digit → VALIDATION_FAILED; CODE128 with a non-ASCII character refused; QR accepts any text; 65 characters refused (`BarcodeFormatTest`, `ProductBarcodesTest`) |
| BR-PROD-003 | U,A | Create product without base unit → 400 |
| BR-PROD-004 | S,A | Archive product; try to add it to a PO / sale → PRODUCT_ARCHIVED; history still readable |
| BR-PROD-005 | U,R | Negative price, max<min threshold → rejected by validation and CHECK; max ≤ reorder point (> 0) accepted with a warning (ADR-092: `thresholds.test.ts`, `ProductPages.test.tsx`, e2e J8) |
| BR-PROD-006 | S | Change price → audit row PRICE_CHANGE with old/new; product prices (`ProductPricesTest`, `PriceAuditFailureTest`) and unit prices incl. clearing to derived (`ProductUnitsTest`, `ProductUnitRulesTest`) |
| BR-PROD-007 | U,R | Negative or duplicate species withdrawal row → rejected |
| BR-UNIT-001 | R,A | Duplicate unit name for same product → DUPLICATE_RESOURCE; factor ≤ 0, > 4 decimals, or not whole for a non-divisible product rejected (`ProductUnitSchemaTest`, `ProductUnitsTest`) |
| BR-UNIT-002 | U | 3 boxes × factor 10 → 30 base; 1.5 × 2.5 = 3.75; 1.25 × 1.25 = 1.5625 refused (SCALE, never rounded, ADR-065); non-whole result for a non-divisible product refused (`UnitConversionTest`) |
| BR-UNIT-003 | S,A | Sell a non-sellable unit → UNIT_NOT_SELLABLE |
| BR-UNIT-004 | S,A | Change factor of unit already used in document lines → UNIT_FACTOR_LOCKED (exercised from Phase 5; Phase 3 tests that an unused unit's factor can change, and with a stubbed `UnitReferences` that a used unit's factor is locked while its other fields stay editable: `ProductUnitsTest`, `ProductUnitRulesTest`); from Phase 5 a unit used on a purchase-order line keeps its factor, `UNIT_FACTOR_LOCKED` (`PurchaseOrdersTest`) |
| BR-UNIT-005 | S,R,A | Scan a box barcode → product, box unit, factor, price, quantity 1; scan a base-unit barcode → unit null; barcode with a unit of another product → refused by service and composite FK; codes containing `/` and `%` found (ADR-071) (`ProductBarcodesTest`, `ProductBarcodeSchemaTest`) |
| BR-LOT-001 | S,A | Receipt line without lot number / expiry → VALIDATION_FAILED, nothing posted |
| BR-LOT-002 | S,R,C | Receive same (product, lot number) twice → one lot, balance summed; another expiry → `LOT_EXPIRY_MISMATCH`, nothing written; recalled → `LOT_RECALLED`; DB rejects a duplicate (product, lot number) even with another expiry (ADR-070); weighted-average cost (ADR-098): empty lot (entry cost replaces), existing lot, opening stock with the same rule; CT-08 two parallel receipts of one new lot → one lot, summed balance, correct cost (Phase 4: `OpeningStockTest`, `OpeningStockConcurrencyTest`) |
| BR-LOT-003 | U,S | Lot expiring yesterday → expired; today and tomorrow → sellable (ADR-055); business date in Africa/Algiers differs from the UTC date around midnight; Phase 3 covers the rule (FEFO preview, alerts), sale confirmation from Phase 7 (`ExpiryPolicyTest`, `LotQueriesTest`) |
| BR-LOT-004 | S | Blocked and recalled lots excluded from FEFO and rejected at confirm |
| BR-LOT-005 | U | Allocation order by expiry then id; skips non-sellable; override without permission → FEFO_VIOLATION (Phase 3: `FefoAllocatorTest`, `FefoAndAlertsTest`; override from Phase 7) |
| BR-LOT-006 | S | Quantity spanning two lots yields two lines, each with one lot |
| BR-LOT-007 | S,R | Lot of another product → LOT_PRODUCT_MISMATCH (Phase 3: service check); composite FK refuses a movement whose product differs from its lot's (Phase 4, ADR-062); line without lot → LOT_REQUIRED (Phase 7) (Phase 3: `LotGuardTest`) |
| BR-LOT-008 | S,A | Recall lot → status recalled, reason/user/time stored, `LOT_RECALL` audited in the same transaction, repeat returns same report; buyers lists empty until Phase 7 (ADR-068), then incl. delivery notes and anonymous sales (`LotActionsTest`, `LotActionAuditFailureTest`) |
| BR-LOT-009 | U,S,R | Every allowed/forbidden stored transition (table test, ADR-060); effective status priority recalled > blocked > expired > depleted > active (ADR-059); DB CHECK refuses `expired`/`depleted` as stored values; recalled lot cannot be unblocked (`LotStatusTest`, `LotSchemaTest`; `LotActionsTest`, `LotActionConcurrencyTest`) |
| BR-LOT-010 | U,S | Days left −1 / 0 / 15 / 16 / 30 / 60 / 61 → expired / critical / critical / warning / warning / notice / none (inclusive, ADR-056); change threshold setting → levels follow; invalid values rejected (`ExpiryPolicyTest`, `LotQueriesTest`, `FefoAndAlertsTest`); each dashboard count equals the size of the list it opens (`FefoAndAlertsTest.eachAlertCountEqualsTheSizeOfItsLotList`, `ExpiryPolicyTest` windows, `LotPages.test.tsx`) |
| BR-LOT-011 | A | No endpoint changes lot number/product/expiry (no write route exists in Phase 3) (`LotQueriesTest`) |
| BR-LOT-012 | S | Expired line refused (`EXPIRED`) unless non-conforming, then blocked lot |
| BR-STOCK-001 | R,C | Direct CHECK violation (3819) mapped to STOCK_INSUFFICIENT; two concurrent sales of last unit → exactly one succeeds; adjustment and regularisation that would go negative refused, nothing posted (`StockServiceTest`, `StockConstraintTranslatorTest`, `StockConcurrencyTest` CT-01, `LotSchemaTest`) |
| BR-STOCK-002 | S | Every stock-changing use case produces exactly the expected movement rows; no class other than `StockService` writes `stock_movements` or lot balances (architecture test, ADR-088); fault injection after the movement insert rolls back movement, balance and document (`StockServiceTest`, `StockAtomicityTest`, `ArchitectureTest.onlyStockServiceWritesStock`) |
| BR-STOCK-003 | R,SEC | UPDATE/DELETE on stock_movements refused (trigger/privileges); failed tx leaves no movement; trigger refuses UPDATE/DELETE by the migration account (SQLSTATE 45000); application account gets error 1142 (ADR-087) (`StockMovementSchemaTest`, `StockMovementAppendOnlyTest`) |
| BR-STOCK-004 | S | Movement row carries user, type, reference, timestamp (`StockServiceTest`, `StockMovementSchemaTest`, `MovementQueriesTest`) |
| BR-STOCK-005 | U,R | Sign per movement type; wrong sign rejected; CHECK accepts exactly the eleven types of ADR-081 (with `receipt_cancellation`, V05_012) (`MovementTypeTest`, `StockMovementSchemaTest`, `StockServiceTest`) |
| BR-STOCK-006 | S,A | Adjustment without reason → ADJUSTMENT_REASON_REQUIRED; reason > 500 → `SIZE` (`StockAdjustmentsTest`, `StockMovementSchemaTest`) |
| BR-STOCK-007 | S,R | Integrity check detects deliberately corrupted balance (test DB only); nightly and on-demand runs stored; mismatch logs CRITICAL and sets the banner; clean run clears it (ADR-086) (`IntegrityCheckTest`, `IntegrityCheckJobTest`) |
| BR-STOCK-008 | U | Adjustment categories map to correct movement types; allowed on blocked, recalled and expired lots; audited `STOCK_CORRECTION` old/new (ADR-085) (`StockAdjustmentsTest`) |
| BR-STOCK-009 | U,A | Threshold validation per product |
| BR-STOCK-010 | U,S | Reorder proposal contents and rounding (ADR-077), no duplicates; trigger, quantity, thresholds incomplete, thresholds inconsistent (max ≤ reorder point), one pending updated in place, dismissed suppressed until recovery (ADR-076…079) (`ReorderQuantityTest`, `ReorderProposalsTest`, `ReorderJobTest`) |
| BR-STOCK-011 | U,R | Sellable stock excludes expired/blocked/recalled/quarantined lots (sellable predicate: `ExpiryPolicyTest`; quantities from Phase 4); product sellable quantity sums only sellable lots; product stock sums only sellable lots (`StockLevelsTest`) |
| BR-STOCK-012 | C | Parallel operations on same lots serialise; opposite lot order does not deadlock; lock wait timeout → `CONCURRENT_MODIFICATION` within 5 s (ADR-012, `LockWaitTimeoutTest`) (`StockConcurrencyTest` CT-02) |
| BR-STOCK-013 | U,A | Status in stock / low / out and below-reorder from sellable quantity and thresholds (ADR-080) (`StockStatusTest`, `StockLevelsTest`) |
| BR-STOCK-014 | S,A | Opening stock: setting closed → `OPENING_STOCK_CLOSED`; new lot; existing lot same expiry adds with the weighted-average cost (ADR-098, from Phase 5; before: other cost refused); other expiry, recalled refused; expired accepted; audited `OPENING_STOCK` (`OpeningStockTest`, `OpeningStockConcurrencyTest`, `SettingFormatTest`) |
| BR-INV-001 | U | variance = counted − theoretical, signs and zero; variance against the balance at the watermark (`CountLineTest`, `InventoryCountsTest`) |
| BR-INV-002 | S | Regularise → one movement per variance line, reason set; none for a zero variance; found lots created; fault injection on the second line and on the audit write rolls back every movement and the created lot (`CountRegularisationTest`, `CountRegularisationAtomicityTest`) |
| BR-INV-003 | S | Full vs cycle counts (ADR-073) produce correct lines; full: lots with stock; cycle by category and by product list (ADR-073); found lots (ADR-074) (`InventoryCountsTest`) |
| BR-INV-004 | S,A | Edit closed count → INVENTORY_NOT_OPEN; regularise twice → INVENTORY_LINE_ALREADY_REGULARIZED; two simultaneous regularisations post once (`InventoryCountsTest`, `CountRegularisationTest`, `CountRegularisationConcurrencyTest`) |
| BR-INV-005 | S | Sale during open count behaves per ADR-072: a sale after the lot was counted (no false variance), a sale before it was counted, a receipt during the count (ADR-072) (`InventoryCountsTest`; sales from Phase 7, manual adjustments meanwhile) |
| BR-INV-006 | SEC,S | Regularise without inventory.validate → ACCESS_DENIED; audited (`EndpointPermissionMatrixTest`, `CountRegularisationTest`) |
| BR-INV-007 | S | Second open full count → INVENTORY_ALREADY_OPEN (`InventoryCountsTest`, `InventorySchemaTest`) |
| BR-INV-008 | S,C | Two edits of one line take two watermarks; regularisation posts the variance on the current balance; a variance that would go negative → `STOCK_INSUFFICIENT`, nothing posted, count open; CT-04; unsellable lots regularised (`InventoryCountsTest`, `CountRegularisationTest`, `CountRegularisationConcurrencyTest`) |
| BR-PURCH-001 | U,S | PO state machine: every illegal transition refused, every legal one checked; cancel and forced close need a reason and are audited (`PurchaseOrdersTest`) |
| BR-PURCH-002 | U,R | Line and PO totals per line, HALF_UP (ADR-093), prices per ordered unit 4 decimals (ADR-094); generated `line_ht`/`line_vat` equal the service for x.xx5 cases, 4-decimal prices and a fractional divisible quantity (ADR-016 parity); client values ignored (`PurchaseOrdersTest`, `PurchaseOrderSchemaTest`) |
| BR-PURCH-003 | A,C | Edit any non-draft PO → DOCUMENT_IMMUTABLE; same product twice → DUPLICATE; stale version → CONCURRENT_MODIFICATION; two parallel edits: one wins, 10 times (`PurchaseOrdersTest`, `PurchaseOrderConcurrencyTest`) |
| BR-PURCH-004 | S,C | Partial receipts update received qty and PO status (closed by full reception, no reason); over-receipt → RECEIPT_QUANTITY_EXCEEDS_ORDER; product not on the order → NOT_ON_ORDER; draft/validated/cancelled/closed order not receivable; another supplier than the order's → NOT_ORDER_SUPPLIER; free receipt accepted; two parallel receipts beyond the outstanding quantity → one refused (`GoodsReceiptsTest`, `GoodsReceiptConcurrencyTest`) |
| BR-PURCH-005 | S | Receipt lines produce lot + positive movement with lot/expiry through `StockService`; ledger = balance; lot number, expiry, quantity and cost validated; one line per lot (`DUPLICATE`) |
| BR-PURCH-006 | S | Non-conforming line → lot blocked (audited); lot with stock → LOT_BLOCK_CONFIRMATION_REQUIRED naming the quantity, accepted with `confirmLotBlocking` |
| BR-PURCH-007 | S | A refusal on line 3 after two lots were created, and separately a failed `LOT_BLOCK` audit write (trigger) → no lots, no movements, no receipt, no price entry, no number, no idempotency key, PO and lot status unchanged (`GoodsReceiptAtomicityTest`, committed data) |
| BR-PURCH-008 | S | Receipt appends a history entry (with its receipt, discount of the PO line, 0 without order) only when price or discount differs from the latest entry in force; manual entry (`SuppliersTest`) |
| BR-PURCH-009 | S,C,R | Supplier return reduces lot (active, blocked, recalled, expired); more than available → STOCK_INSUFFICIENT; lot of another supplier → OTHER_SUPPLIER, lot without supplier accepted; inactive supplier accepted; reason / lines / lot `DUPLICATE` / quantity validated; audited RETURN_CREATE; two parallel returns overdrawing one lot ×20 → one refused, no SR number consumed (`SupplierReturnsTest`, `SupplierReturnConcurrencyTest`, `SupplierReturnSchemaTest`) |
| BR-PURCH-010 | S | Convert several proposals into one draft PO per supplier (base unit, suggested quantity, price from the supplier's history else the product's purchase price); proposals converted with their supplier; twice, or a dismissed proposal → INVALID_STATE_TRANSITION; inactive supplier, archived product, group validation; generation subtracts validated/sent/partially received orders, not drafts, proposes nothing when they cover the need and leaves a pending proposal as is (ADR-102; `ReorderConversionTest`, `ReorderQuantityTest`) |
| BR-PURCH-011 | S | PO / receipt for inactive supplier → SUPPLIER_INACTIVE at creation and validation (archived product → PRODUCT_ARCHIVED); deactivate and reactivate (`SuppliersTest`, `PurchaseOrdersTest`); name unique ignoring case and accents (`SupplierSchemaTest`) |
| BR-PURCH-012 | S,C | Cancel a receipt: reversal movements, PO rolled back, audited, number kept; a later movement on one lot → RECEIPT_NOT_CANCELLABLE naming it; fault injection rolls everything back; created lot stays at 0 without supplier; cost restored; status restored only if unchanged — block/unblock/recall between receipt and cancellation leave it; forced-closed order stays closed; price-history entry marked cancelled and ignored by the prefill (ADR-106) | (`GoodsReceiptCancellationTest`; a failed `LOT_UNBLOCK` audit in `GoodsReceiptAtomicityTest`; two parallel cancellations and a cancellation racing a withdrawal, 20 times each, in `GoodsReceiptCancellationConcurrencyTest`). A lot the receipt created and blocked is unblocked as well (its status at creation was active). A lot the receipt did not block, then blocked or recalled by a person, keeps that status (`GoodsReceiptCancellationTest.aLotBlockedOrRecalledByAPersonAfterTheReceiptKeepsThatStatus`); a cancellation racing a supplier return of the same lot ×20 never deadlocks despite the lock-order exception (`aCancellationRacingASupplierReturnOfTheSameLotNeverDeadlocks`).
| BR-PURCH-013 | S | Same key + same body → first receipt, nothing posted twice; same key + other body → IDEMPOTENCY_CONFLICT (`GoodsReceiptsTest.theSameIdempotencyKeyReturnsTheFirstReceipt`, CT-05 in `IdempotencyKeysTest`) |
| BR-DOC-001 | C,R | CT-03: 1000 concurrent numbers unique and gap-free on commit, 20 times; format `KEY-YYYY-NNNNNN`; yearly reset on the Algiers business year (31 Dec 23:30 UTC = next year); no number without a transaction; a rolled-back transaction leaves no gap; the counter stays locked until commit; number then lots in opposite orders never deadlock, 20 times (`NumberingServiceTest`, `NumberingTransactionTest`) |
| BR-SALE-001 | U | Document chain and allowed conversions |
| BR-SALE-002 | S | Quote posts none; delivery note, receipt and direct invoice post `sale`; invoice from a delivery note reuses its lots and posts no second movement (ADR-117); one delivery note gives one invoice, no second limit check, cancelling that invoice posts no movement (ADR-125) |
| BR-SALE-003 | S | Confirm quote → no movement |
| BR-SALE-004 | A | Edit confirmed document → DOCUMENT_IMMUTABLE |
| BR-SALE-005 | S | Lots assigned and verified at confirmation |
| BR-SALE-006 | U,I | Price precedence (ADR-107): manual > customer's own price > unit price > product × factor; category discount only without a customer price; manual discount on top within the entitlement; unit conversion; customer without category; archived product; no rounding — `PriceResolverTest` (table), `CustomerPricingTest` (stored facts) |
| BR-SALE-007 | U,SEC | Discount above entitlement needs sale.discount; no stacking (ADR-113); a manual price needs price.edit; the rights come from the signed-in user — `PriceResolverTest`, `CustomerPricingTest` |
| BR-SALE-008 | U | Server totals identical for same input; client totals ignored |
| BR-SALE-009 | S | Line stores unit cost snapshot equal to lot cost |
| BR-SALE-010 | S | On screen: warnings for any customer, by species, every species when none is given, none when no data (ADR-121); printing from Phase 10 (OD-028) |
| BR-SALE-011 | S | Archived product in sale → PRODUCT_ARCHIVED |
| BR-SALE-012 | S | Credit sale without customer; inactive customer → errors |
| BR-SALE-013 | S | A payment with a cash part needs an open session; cheque, transfer and credit sales do not (ADR-118) |
| BR-SALE-014 | S | ADR-119: whole document; refused with a return, credit note, derived document or closed session; `sale_cancellation` movements, payments reversed, number kept, audit, atomic |
| BR-SALE-015 | C | Confirm with insufficient stock → STOCK_INSUFFICIENT, atomic rollback |
| BR-SALE-016 | S,A | Double submit with same Idempotency-Key → one document; different body → IDEMPOTENCY_CONFLICT |
| BR-CUST-001 | A | Veterinarian link: an active customer of type veterinarian (`NOT_VETERINARIAN`), not itself (`SELF`); a referenced veterinarian's type cannot change (`RESOURCE_IN_USE`); deactivating it keeps the links (`CustomersTest`) |
| BR-CUST-002 | S,A,C,F,E | New customer at 0 (shown "Cash only"); the dialog shows three explicit states — no limit (sends `null`), cash only (`"0.00"`), a limit (zero typed as a limit refused) — tested in fr, ar (RTL) and en, its button only with customer.credit.manage (`CustomerPages.test.tsx`, e2e J12); a `creditLimit` sent on create or update is ignored; Seller (customer.manage) and Pharmacist cannot change it; NULL accepted; negative, 3 decimals, text and a missing field refused; every change, to and from NULL, audited with old and new value (amounts only); stale version refused; a failed audit rolls the change back; two parallel changes ×20 → one CONCURRENT_MODIFICATION (`CustomerCreditLimitTest`, `CustomersTest`, `CustomerCreditAtomicityTest`) |
| BR-CUST-003 | A | `DELETE /api/customers/{id}` → 405; deactivate and reactivate; edit with the version (`CustomersTest`) |
| BR-CUST-004 | S | Sale to inactive customer → CUSTOMER_INACTIVE |
| BR-CUST-005 | U,S,R,C | Phone and tax-id normalisation unit tests; same name + phone or same tax id → DUPLICATE_RESOURCE with ids and field only; same name or phone alone → similar-customers warning; two parallel creations of a duplicate → one refused (unique keys); the same name without a phone → both accepted, also in parallel (`CustomerKeysTest`, `CustomersTest`, `CustomerConcurrencyTest` 2×20); personal data never in logs, no audit entry for customer edits (`CustomersTest.customerPersonalDataNeverReachesTheLogs`, ADR-114) |
| BR-CUST-006 | S,C | Set, change (version), remove a customer price, each audited PRICE_CHANGE old/new (product id and amounts only); > 0, 4 decimals; archived product refused, existing kept and removable; inactive customer editable; Seller refused; a failed audit writes nothing; two parallel creations of one price ×20 → one row, the other CONCURRENT_MODIFICATION (`CustomerPricesTest`, `CustomerPriceAtomicityTest`) |
| BR-CUST-007 | S | Category name unique ignoring case and accents; discount bounds and scale; discount change audited old/new, not a rename nor the initial discount; stale version → CONCURRENT_MODIFICATION; no delete (405); a Seller cannot change a discount (`CustomerCategoriesTest`) |
| BR-RET-001 | S | Return > sold − already returned → RETURN_QUANTITY_EXCEEDED |
| BR-RET-002 | S | Opened/non-sealed item cannot be restocked (OD-005) |
| BR-RET-003 | S | Restock movement targets original lot; discard path per OD-005 |
| BR-RET-004 | S | Credit note created with return, number issued, amounts match |
| BR-RET-005 | S | Fault after movement → everything rolled back |
| BR-RET-006 | S | Refund/settlement per OD-005 |
| BR-PAY-001 | U,A | Amount ≤ 0; missing reference for cheque/transfer → VALIDATION_FAILED |
| BR-PAY-002 | S | Allocation > payment or > outstanding → PAYMENT_ALLOCATION_EXCEEDED |
| BR-PAY-003 | SEC,A | Payment update/delete refused; reversal creates counter entry |
| BR-PAY-004 | S,R | Balance = credit invoices − their credit notes − account payments; counter payments never count (ADR-118); view parity test |
| BR-PAY-005 | S,C | Credit above limit → CREDIT_LIMIT_EXCEEDED; concurrent sales respect limit; NULL = no limit, 0 = cash only; overdue blocks only with `credit_overdue_block_days` (ADR-108); uninvoiced credit delivery notes count in the check, not in the balance (ADR-117) |
| BR-PAY-006 | S | Cash payment with no open session → CASH_SESSION_REQUIRED |
| BR-PAY-007 | S | Statement lines and running balance over a period |
| BR-PAY-008 | R,A | Duplicate (supplier, invoice number) → DUPLICATE_RESOURCE |
| BR-PAY-009 | S | Supplier payment > remaining → PAYMENT_ALLOCATION_EXCEEDED |
| BR-PAY-010 | S | Advance recorded and later allocated |
| BR-PAY-011 | S | Supplier credit note reduces payable |
| BR-CASH-001 | R,S | Second open session for same cashier → CASH_SESSION_ALREADY_OPEN (unique via generated column) |
| BR-CASH-002 | S | Close: variance = counted − expected |
| BR-CASH-003 | U,S | Expected = opening + cash in − cash out (cash only); a cash sale with change posts the sale total, never the amount tendered (ADR-118) |
| BR-CASH-004 | A | Modify closed session → CASH_SESSION_CLOSED |
| BR-CASH-005 | S | Movement amount ≤ 0 / wrong direction rejected |
| BR-SEC-001 | SEC | Every endpoint (enumerated) without credential → 401 |
| BR-SEC-002 | SEC | Permission matrix test: each endpoint × role → expected 200/403 |
| BR-SEC-003 | SEC,U | Password policy; stored hash never plaintext; never in logs/responses |
| BR-SEC-004 | SEC | Disabled user cannot log in / existing credential refused |
| BR-SEC-005 | SEC | `login_max_failures` failures → RATE_LIMITED even with the right password; other IP or username unaffected; window/lockout expiry with the test `Clock` (ADR-038) |
| BR-SEC-006 | R,SEC | App DB account lacks DDL (error 1142 on CREATE/ALTER/DROP) and UPDATE/DELETE on append-only tables (1142); every table granted after each migration; the trigger still refuses the migration account (45000) (`DatabaseAccountsTest`, `AuditLogAppendOnlyTest`, ADR-087) |
| BR-AUDIT-001 | S | Each audited action code produces an audit row (table-driven) |
| BR-AUDIT-002 | SEC,R | Audit rows cannot be updated or deleted; contain required fields |
| BR-AUDIT-003 | S | Force audit failure → business action rolled back |
| BR-RPT-001 | S,R | Revenue and margin net of credit notes |
| BR-RPT-002 | S | Valuation by cost vs sale price on known fixture |
| BR-RPT-003 | SEC | Report endpoints require their permissions |
| BR-BKP-001 | S | Backup job produces log entry; failure recorded (OD-021) |
| BR-BKP-002 | S,A | Exports (CSV/PDF) respect permissions and filters |

## 10. Architecture and quality gates

- **Architecture tests** (ArchUnit-style, from Phase 0): module dependency rules (`architecture.md` §3); controllers never touch repositories or entities; only `inventory` writes `stock_movements`; only `finance` writes payments; no `double`/`float` for money or quantity; no `@Transactional` on controllers.
  - *Implemented in Phase 0* (`backend/src/test/java/com/imsvet/ims/architecture/ArchitectureTest.java`): module dependencies (`auth → users` is the only edge between those two, to keep them acyclic), no cycles, `common` depends on no module, `api` never uses `infrastructure` or `@Entity` classes, `domain` never uses `api`/`application`, `application` never uses `api`, no `@Transactional` on controllers, **no `double`/`float` field anywhere in main code**. The "only `inventory` writes stock" and "only `finance` writes payments" rules are added with the first ledger/payment classes (Phases 4 and 9).
- **Static analysis:** compiler warnings as errors (`-Xlint:all -Werror`), lint (oxlint, the Vite template's linter, instead of ESLint — Phase 0) and TypeScript `strict`; a formatter check is not configured yet.
- **Coverage guidance `[REC]`:** domain and application packages ≥ 85 % line coverage, but the rule matrix (§9) is the real gate.
- **CI pipeline stages:** build → unit → integration (Testcontainers) → API/security → migration tests → frontend unit → e2e (Playwright against the real backend on a MySQL container, its own job on every push and pull request while the journeys stay short; move to nightly if they grow) → dependency/secret scans.

## 11. Module Definition of Done

A module/phase is complete only when:
1. All its requirements (`requirements.md`) are implemented and traceable in the roadmap.
2. All its business rules (§9) have passing tests, including failure paths; `@Disabled` tests are zero or each linked to a recorded OPEN DECISION that is not in the phase scope.
3. Every endpoint of the module has success, validation, 401, 403 and business-error tests, and matches `api.md` (or the doc was updated in the same change).
4. Security: permissions applied and tested; audit rows verified.
5. Migrations are small, applied on empty and previous-state DBs, and pass validation (`ddl-auto=validate`).
6. Concurrency tests relevant to the module pass repeatedly.
7. Frontend pages for the module have component tests and the relevant e2e journey passes.
8. Documentation updated (`api.md`, `database.md`, `decisions.md`, glossary) and `documentation-review.md` items for the module are closed or consciously deferred.
9. No TODO without an issue reference; no commented-out code; no secrets.
10. Code reviewed and merged via the Git workflow in `development-workflow.md`.

## 12. Migration-specific tests (PostgreSQL → MySQL)

See `database-migration.md` §10 checklist. Every migration runs on an empty database (`BaselineMigrationTest`) and, for Phase 5, on a database holding Phase 4 data migrated to V04_009 first (`Phase5UpgradeMigrationTest`: balances equal the ledger after the upgrade, new columns empty, new constraints in force); for Phase 6, on Phase 5 data migrated to V05_016 (`Phase6UpgradeMigrationTest`: balances equal the ledger, the document sequence keeps its number, the customer tables start empty, a new customer is cash only, the credit-limit, price and discount CHECKs and the product RESTRICT apply). Automated: schema snapshot equals expected; enum CHECK sets match the application enums; view outputs equal a reference calculation on a fixture; collation behaviour documented by tests; trigger tests for append-only and sign rules; generated-column values; seed data present.

## 13. Open testing items

ADR-107, ADR-093 and ADR-115 (price and rounding fixtures), ADR-117…119 and OD-005 (sales/payment/cancellation/return expectations), OD-012/013/015/017 (resolved), ADR-108.
