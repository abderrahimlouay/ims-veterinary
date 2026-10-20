# Documentation Review — Cross-Document Consistency Audit

> **Status:** Documentation / Architecture Phase — Implementation Not Started
> **Purpose:** record every inconsistency, gap, ambiguity and risk found while reading the four inputs (Spec `said_project.md`, Source Schema `ims_veterinaire_schema_v2.sql`, design prototype, master brief) and while producing the documentation set. Nothing here is silently resolved.
> **Last updated:** 2026-10-20

Severity scale: **Critical** (would cause data corruption/legal/financial error or blocks a phase if ignored) · **High** (wrong behaviour likely) · **Medium** · **Low** · **Open Decision** (needs the user; see `decisions.md`).
Status values: *Open* (nothing decided), *Proposed* (resolution recommended, awaiting approval), *Mitigated* (handled in the docs, implementation must follow).

## 1. Summary

| Severity | Count |
|---|---|
| Critical | 3 |
| High | 10 |
| Medium | 14 |
| Low | 4 |
| Open Decision (not tied to a defect) | 2 |
| **Total** | **33** |

Gate for implementation: **Critical and High items marked for Phase 0–1 must be reviewed by the user before Phase 0 starts**; later items must be resolved before their phase (column *Needed by*).

## 2. Issue register

| ID | Issue | Severity | Affected documents | Description | Recommended resolution | Needed by / Link |
|---|---|---|---|---|---|---|
| DR-001 | Numbering keys inconsistent | High | database.md, database-migration.md, business-rules.md (BR-DOC-001) | Source Schema documents `doc_key` values `PO, INV, RCT, QUO, DN, CN` but the function is called with `PO, GR, SR, CR`; `sales_documents.number` has no default. The numbering key scheme, per-year reset and treatment of abandoned drafts are undefined. | Define the key list and format once (OD-017); numbering allocated by `NumberingService` only at issue time for sales documents. | Phase 5 · OD-017, ADR-011 |
| DR-002 | Movement types lack cancellation and discard | High | database-migration.md §4, business-rules.md, workflows.md | `movement_type` has no value for cancelling a confirmed sale or for discarding returned opened goods; `unit_conversion` exists without use. Cancellation (Spec silent) and discard (FR-SALE-006) cannot be ledgered correctly. | Add explicit types after OD-004 / OD-005 (SD-1xx); never reuse `adjustment_*` for these. | Phase 7–8 · OD-004, OD-005 |
| DR-003 | Roadmap dependency: POS needs payments/cash | High | roadmap.md | Phase 7 (POS) requires payment capture and an open cash session (BR-SALE-013, BR-PAY-006) that belong to Phase 9. | Option (a): minimal counter-payment/cash-session slice in Phase 7 (assumed in roadmap) or (b) reorder phases. Choose with OD-003. | Phase 7 · OD-003 |
| DR-004 | Delivery notes ignored by recall and stock-moving rule unclear | Critical | database.md, workflows.md (W6), business-rules.md (BR-SALE-002, BR-LOT-008) | The recall function in the Source Schema finds buyers through invoices only; delivery notes (which may move stock) are missed, and whether quote/delivery note/invoice/receipt each move stock is not specified, risking double decrement or incomplete recall. | Decide which documents move stock (OD-002). Rewrite the recall query in Spring over all stock-moving documents plus anonymous sales (done in W6). | Phase 7 · OD-002 |
| DR-005 | Payment capture hole for counter and anonymous sales | Critical | database.md, business-rules.md (BR-PAY-004/006), workflows.md, api.md §5.6 | The schema records payments only against customers; a cash sale to no customer has no payment record, so cash sessions and revenue cannot reconcile. | Define payment capture for all sales (OD-003) and the cash-session link; confirm payload in `api.md` follows the decision. | Phase 7 · OD-003 |
| DR-006 | `v_customer_balance` is defective | Critical | database-migration.md, database.md, decisions.md (ADR-014) | The view definition (credit invoices − credit notes − payments) mishandles cash sales, unallocated payments and returns on cash sales; it can produce wrong balances that gate credit limits. | Do not port as is; redefine after OD-003; test parity against domain computation. | Phase 9 · OD-003 |
| DR-007 | `v_sales_margin` ignores credit notes and delivery-note semantics | High | database-migration.md, database.md, business-rules.md (BR-RPT-001) | Margin and revenue overstated when returns exist. | Rewrite view net of credit notes; define scope per OD-002/003. | Phase 11 |
| DR-008 | Lot–product consistency not enforced | Medium | database-migration.md (SD-103), business-rules.md (BR-LOT-007) | Movement, receipt and sale lines can reference a lot belonging to another product. | Composite uniqueness on `lots(id, product_id)` and composite FKs (SD-103) plus service check; error `LOT_PRODUCT_MISMATCH`. | Phase 3 |
| DR-009 | Supplier returns and settlements have no financial model | Medium | workflows.md (W4), business-rules.md (BR-PAY-009…011), database.md | Supplier return credits, refunds and supplier credit notes are not linked in the schema. | OD-016 defines settlement; add relation or document the manual process. | Phase 9 · OD-016 |
| DR-010 | Search by animal species has no data path | Medium | api.md §4.3, database.md, requirements.md (FR-SALE-002) | The Spec requires search by species but products relate to species only through withdrawal periods. | OD-018: use withdrawal-period link, add explicit product–species relation, or drop the criterion. | Phase 2 · OD-018 |
| DR-011 | Barcode per packaging unit | Medium | database.md, api.md | Barcodes attach to products; scanning a box vs a blister needs unit resolution. | OD-019; optional `barcodes.unit_id`. | Phase 3 · OD-019 |
| DR-012 | Numeric precision and unit-conversion rounding | Medium | database.md, business-rules.md (BR-UNIT-002) | Scales (qty 3, cost 4, money 2) and rounding mode are not defined by the Spec; sub-unit sales with non-integer factors can create fractional quantities. | OD-022 and OD-007; fixed scales in `api.md` §1 are `[REC]`. | Phase 2 · OD-022 |
| DR-013 | Availability requirement is empty | Low | requirements.md (NFR-004) | Spec heading "Disponibilité et Impression" gives no availability figure. | Ask for RTO/RPO and hours (OD-021). | Phase 12 |
| DR-014 | Design prototype conflicts with Spec locale and gaps in tokens | Medium | frontend.md §6 | Prototype shows EUR, generic addresses; Spec targets Algeria (wilaya/commune, NIF). No dark theme; focus contrast and some small text unverified. | Currency/locale **resolved by ADR-033** (2026-10-20); token set completed and checked in Phase 0. | Phase 0 · ADR-033 |
| DR-015 | Prototype hard-codes expiry and stock thresholds | Medium | frontend.md, requirements.md (FR-LOT-005) | Alert thresholds in the prototype are constants; Spec says configurable. | Read thresholds from `/api/settings/public`. | Phase 3 |
| DR-016 | Prototype accessibility gaps | Medium | frontend.md §5, §12 | `Alert` lacks `role="alert"`; some statuses use colour only. | Fix in the component library; test with axe. | Phase 0 |
| DR-017 | Expiry boundary and stored `expired` status | High | business-rules.md (BR-LOT-003, BR-LOT-009), domain-model.md | Whether a lot is sellable on its expiry date is not specified; a stored `expired` status can disagree with the calendar if a job misses a run. | OD-009 boundary; rule evaluated at consume time, status only a cache (OD-027). | Phase 3 · OD-009, OD-027 |
| DR-018 | `unit_conversion` movement type unused | Low | database-migration.md | Stock is kept in base units only; the type has no defined use. | Keep as reserved or remove; decide in Phase 3 (SD register). | Phase 3 |
| DR-019 | Expiry view hard-codes 15/30/60 | Medium | database-migration.md, database.md | `v_expiring_lots` uses fixed thresholds while the Spec says configurable (FR-LOT-005, FR-SEC-003). | Compute level in Spring from `app_settings`; keep the view for listing only. | Phase 3 |
| DR-020 | Withdrawal-warning applicability | Medium | business-rules.md (BR-SALE-010), workflows.md | Spec: warnings for "breeders"; unclear if they apply to every customer type, species unknown, or non-food animals. | OD-028. | Phase 10 · OD-028 |
| DR-021 | Company identity absent from data model | Low | database.md, frontend.md | Legal documents need company name, tax id, address, logo; no table or setting exists. | OD-026; add settings keys. | Phase 10 · OD-026 |
| DR-022 | Lot cost on repeated receipt | High | business-rules.md (BR-LOT-002), database.md | Receiving the same lot again at a different cost: overwrite, weighted average, or separate lot? Affects margin and valuation. | OD-029. | Phase 5 · OD-029 |
| DR-023 | Non-conforming goods and over-receipt | Medium | business-rules.md (BR-PURCH-004/006) | No quarantine state or tolerance for over-receipt in the schema/Spec. | OD-014, OD-015. | Phase 5 |
| DR-024 | Case/accent-insensitive collation changes uniqueness | High | database-migration.md, decisions.md (ADR-024) | MySQL default collation makes `SKU`, `lot_number`, usernames case- and accent-insensitive unlike PostgreSQL. | OD-025; tests documenting behaviour (ADR-024). | Phase 3 · OD-025 |
| DR-025 | Credit-limit semantics | High | business-rules.md (BR-PAY-005), workflows.md | Whether the limit includes the current sale, unconfirmed drafts, or only overdue amounts is not specified. | OD-011. | Phase 6 |
| DR-026 | Inventory count vs live sales | High | workflows.md (W5), business-rules.md (BR-INV-005) | Frozen theoretical quantity vs live adjustment; scope of cycle counts unspecified. | OD-012. | Phase 4 |
| DR-027 | Reorder algorithm unspecified | Medium | business-rules.md (BR-STOCK-010), workflows.md (W10) | Spec gives thresholds but not the proposed quantity or supplier choice. | OD-013. | Phase 4 |
| DR-028 | Refund treatment of returns | Medium | workflows.md (W3), api.md §5.8 | Cash refund vs credit on account vs discard write-off unspecified. | OD-005. | Phase 8 |
| DR-029 | Cancellation of confirmed documents | Open Decision | business-rules.md (BR-SALE-014), workflows.md (W2b), api.md | Status `cancelled` exists in the schema but the Spec does not describe cancelling confirmed documents (stock reversal, numbering). | OD-004; endpoint marked [OPEN]. | Phase 7 · OD-004 |
| DR-030 | Hosting and backup unspecified | Open Decision | requirements.md (FR-AUD-002), architecture.md §12 | Backup frequency, retention, off-site copy and restore objectives are not specified. | OD-021. | Phase 11 · OD-021 |
| DR-031 | Authentication mechanism and permission matrix | High | security.md, api.md §4.1, frontend.md §8 | Spec requires "secure authentication" and roles but not the mechanism nor the rights per role; the matrix in `security.md` is a proposal. | OD-001, OD-023 must be decided before Phase 1. | Phase 1 · OD-001, OD-023 |
| DR-032 | Backend module list differs between ADR-006 and backend.md/architecture.md | Medium | decisions.md (ADR-006), backend.md §2, architecture.md §2.2/§3 | ADR-006 lists 18 modules (`product`, `user`, `supplier`, `customer`, `returns`, `payment`…); the other documents and the dependency rules use 14 (`catalog`, `users`, suppliers in `purchasing`, customers/returns in `sales`, payments in `finance`). | **Resolved 2026-10-20 by ADR-034** (user decision: backend.md list). | Phase 0 · ADR-034 |
| DR-033 | Flyway file-name convention differs | Low | decisions.md (ADR-018), development-workflow.md §6 | ADR-018 shows `V001__…`; the workflow prescribes `V<phase>_<seq>__…`. | **Resolved 2026-10-20** (user decision: `V<phase>_<seq>__`, ADR-018 amended). | Phase 0 · ADR-018 |

## 3. Automated consistency audit (executed 2026-10-18)

A scripted audit (read-only, over all `.md` files) checked the following. Re-run at the end of every phase (`development-workflow.md` §9).

| # | Check | Result |
|---|---|---|
| 1 | Every business rule ID in `business-rules.md` (110) appears in the `testing.md` matrix | Pass — 110/110, no extra IDs |
| 2 | Every FR in `requirements.md` appears in the `roadmap.md` matrix | Pass |
| 3 | Every FR/NFR/BR/OD/ADR/SD reference used anywhere is defined | Pass |
| 4 | Business-rule error codes are defined in `api.md` §3 | Pass (`INVOICE_CANCEL`, `STOCK_CORRECTION` are audit action codes, not errors; `LOT_NEAR_EXPIRY`, `WITHDRAWAL_PERIOD`, `LOW_STOCK_AFTER_SALE` are non-blocking warning codes, deliberately outside the error catalogue) |
| 5 | Permission codes used in `api.md` / `frontend.md` exist in `security.md` §4 | Pass |
| 6 | All 44 source tables appear in `database.md` | Pass — 44/44 |
| 7 | PostgreSQL syntax absent outside `database-migration.md` | Pass (mentions in `decisions.md` ADR-010/014 are descriptive context, not DDL) |
| 8 | DR references used in other documents are defined here | Pass after correction (the roadmap cited DR-001/DR-002 for the view defects; corrected to DR-006/DR-007) |
| 9 | Section cross-references (`§n`) point to existing sections | Pass after four corrections (database-migration §9/§10, §4 enum section; security §7/§12) |

### 3.1 Corrections made during the audit
- `roadmap.md`: DR-001/002 references replaced by DR-006/007.
- `testing.md`, `roadmap.md`, `decisions.md`, `backend.md`, `frontend.md`: section references aligned (BR-SEC-002 for UI gating).
- `frontend.md`: removed an undefined assumption ID for offline mode; stated as a recommendation instead.

### 3.2 Known limits of the audit
It verifies identifiers and references, not the semantic correctness of rules. Semantic review of rule wording against the Spec remains a human task before Phase 0. The permission matrix, scales, thresholds and timing targets marked `[REC]` are proposals, not requirements.

## 4. Traceability snapshot

| From | To | Coverage |
|---|---|---|
| Spec requirement (FR/NFR) | Roadmap phase | 100 % |
| Business rule | Test matrix row | 100 % (110/110) |
| Source table | `database.md` | 100 % (44/44) |
| Workflow W1–W12 | API endpoints (`api.md` §4) and tests (`testing.md` §5) | covered; endpoints depending on OD marked `[OPEN]` |
| Open decision OD-001…029 | `decisions.md` Part B and Part C phase gate | 100 % |

## 5. Items the user should decide first

For Phase 0: OD-024 (versions) and OD-008 (language/currency/locale) — both decided 2026-10-20 (ADR-032, ADR-033). For Phase 1: OD-001 (authentication) and OD-023 (permission matrix). The largest delivery risk is the group OD-002/OD-003/OD-004 (stock-moving documents, payment capture, cancellation) because it shapes the POS and finance modules (DR-004, DR-005, DR-006).
