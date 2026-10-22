# Business Rules Catalogue — IMS Vétérinaire

> **Status:** Documentation / Architecture Phase — Implementation Not Started
> **Last updated:** 2026-10-18

Single catalogue of business rules. Every rule has an identifier used in code comments, test names (`@Tag("BR-STOCK-001")`) and commit messages.

## 1. Reading guide

**Basis tag** (last column) tells where the rule comes from — nothing here is silently invented:

| Tag | Meaning |
|---|---|
| `SRC` | Stated in the Spec (functional specification) — requirement id given |
| `SCH` | Derived from the Source Schema (constraint, comment, view logic) |
| `REC` | **Architectural recommendation** by the documentation author — not a source requirement |
| `OD-nnn` | Depends on an Open Decision (`decisions.md`); the rule text is the leading hypothesis only and must not be implemented before the decision |

**Enforcement layer:** `DB` = constraint/trigger/generated column; `SVC` = Spring service/domain code inside the transaction; `API` = request validation (Bean Validation) at the controller; `UI` = frontend assistance only (never the sole enforcement); `JOB` = scheduled job; `OPS` = infrastructure/operations.
Per ADR-007 every rule marked `SVC` is authoritative in the backend; `UI` is a convenience.

**Error codes** are the stable `code` values of the error response (`api.md` §3).

### 1.1 Critical rules requested in the brief → identifiers

| Required rule | Identifier(s) |
|---|---|
| Stock cannot become negative | BR-STOCK-001, BR-SALE-015 |
| Expired products cannot be sold | BR-LOT-003 |
| Recalled/blocked lots cannot be sold | BR-LOT-004, BR-LOT-008 |
| FEFO must be respected | BR-LOT-005 |
| Every received lot must have lot number and expiry | BR-LOT-001, BR-PURCH-005 |
| Sales must remain traceable to lots | BR-LOT-006, BR-LOT-007, BR-SALE-005 |
| Stock movements historically traceable | BR-STOCK-002, -003, -004 |
| Inventory adjustments require a reason | BR-STOCK-006, BR-INV-002 |
| Financial operations preserve history | BR-PAY-003, BR-CASH-004 |
| Invoice/document numbering unique | BR-DOC-001 |
| Credit limits respected | BR-PAY-005 |
| Sensitive operations audited | BR-AUDIT-001…003 |

---

## 2. Product catalogue (PROD, UNIT)

| ID | Name | Description | Applies to | Trigger | Validation | Enforcement | Error | Basis |
|---|---|---|---|---|---|---|---|---|
| BR-PROD-001 | SKU unique | A SKU identifies exactly one product; comparison is case- and accent-insensitive (ADR-051). | product | create / update product | non-blank, unique | DB (UNIQUE) + SVC | PRODUCT_SKU_DUPLICATE | SRC FR-PROD-003 |
| BR-PROD-002 | Barcode unique | A barcode value belongs to one product only, compared exactly and case-sensitively (ADR-058); it may designate a packaging unit of that product (ADR-057). | product barcode | add / change barcode | non-blank, unique across all products | DB (UNIQUE, `utf8mb4_bin`) + SVC | BARCODE_DUPLICATE | SRC FR-PROD-003 · ADR-057, ADR-058 |
| BR-PROD-003 | Base unit mandatory | Every product defines a base unit; all stock quantities are expressed in it. | product | create product | non-blank | DB (NOT NULL) + API | VALIDATION_FAILED | SCH |
| BR-PROD-004 | Archived products are inactive | Archived products are refused on new purchase orders, sales documents and receipts; they stay visible in history and reports and remain editable (user decision 2026-10-21). | product | archive; any document creation | `is_archived = false` for new documents | SVC | PRODUCT_ARCHIVED | SRC FR-PROD-001 + REC |
| BR-PROD-005 | Price and threshold validity | Prices ≥ 0 (per base unit, 4 decimals, ADR-049), VAT ≥ 0, min/max/reorder ≥ 0 and whole numbers for non-divisible products (ADR-050), `max_stock ≥ min_stock`. | product | create / update | numeric bounds | DB (CHECK) + API | VALIDATION_FAILED | SCH |
| BR-PROD-006 | Price changes audited | Any change to `sale_price`, `purchase_price`, unit price or per-customer price records old and new value; the initial prices given at creation are not a change and are not audited; setting or changing a price needs `price.edit`. | product, product unit, customer price | update of a price field | audit entry written in same transaction | SVC | — (a failed audit aborts the change) | SRC FR-AUD-001 |
| BR-PROD-008 | Barcode format | `code_type` ∈ EAN13, CODE128, QR; ≤ 64 characters; EAN13 = 13 digits with a valid check digit; CODE128 = printable ASCII; QR = text without control characters (ADR-067). | product barcode | add barcode | format by type | DB (CHECK on type) + API | VALIDATION_FAILED | REC · ADR-067 |
| BR-PROD-007 | Withdrawal-period integrity | One withdrawal record per (product, species); days ≥ 0; a missing value means "not specified", not zero. | product | create / update withdrawal period | uniqueness, bounds | DB (PK, CHECK) + API | VALIDATION_FAILED | SRC FR-PROD-005 + REC |
| BR-UNIT-001 | Unit definition | `factor_to_base > 0` with at most 4 decimals, and a whole number for a non-divisible product (ADR-050, ADR-065); unit name unique within the product. | product unit | create / update unit | bounds, uniqueness | DB + API | VALIDATION_FAILED / DUPLICATE_RESOURCE | SCH |
| BR-UNIT-002 | Server-side unit conversion | `quantity_base = quantity × factor_to_base` (factor 1 for the base unit; `unit_id` NULL = base). The client's `quantity_base` is ignored. Result must be > 0 and is never rounded: a result needing more than 3 decimals is refused (ADR-065). For a product that is not divisible (ADR-050) every base quantity must be a whole number. | PO line, receipt line, sale line, return line, FEFO preview | any document line save | recompute, bound | SVC | VALIDATION_FAILED | SRC FR-PROD-006/007 · ADR-050, ADR-065 |
| BR-UNIT-003 | Sellable units only | Only units with `is_sellable = true` (or the base unit) may appear on sales lines. Purchase lines may use any unit. | sales line | add / edit sale line | unit flag | SVC | UNIT_NOT_SELLABLE | SCH |
| BR-UNIT-004 | Unit factor locked once used | `factor_to_base` of a unit referenced by any document line cannot change; define a new unit instead. The check exists from Phase 3 but can only be exercised once document lines exist (Phase 5). | product unit | update unit | no references | SVC | UNIT_FACTOR_LOCKED | REC |
| BR-UNIT-005 | Barcode resolves to a unit | A barcode may name a packaging unit of its own product (`unit_id`; none = base unit). A scan returns the product, that unit with its factor and price (ADR-063), and a quantity of 1 of that unit. | barcode scan | scan lookup | unit belongs to the product | DB (composite FK) + SVC | NOT_FOUND / VALIDATION_FAILED | REC · ADR-057 |

## 3. Lots and traceability (LOT)

| ID | Name | Description | Applies to | Trigger | Validation | Enforcement | Error | Basis |
|---|---|---|---|---|---|---|---|---|
| BR-LOT-001 | Lot identity mandatory on stock entry | Every stock entry (goods receipt, opening stock, stock found by inventory) supplies the manufacturer lot number **and** expiry date. | goods receipt line, opening stock | receipt / opening confirm | lot number non-blank, expiry present | API + SVC (+ DB NOT NULL on `lots`) | VALIDATION_FAILED | SRC FR-LOT-001/003 |
| BR-LOT-002 | Lot uniqueness, find-or-create | A lot is unique per (product, lot number) (ADR-070). Receiving an existing lot number increases that lot's balance (under lock); an expiry date different from the existing lot's is refused and changes nothing. Cost policy on a repeated receipt at a different cost: **OD-029**. | lot | receipt confirm | unique key, same expiry | DB (UNIQUE) + SVC | LOT_EXPIRY_MISMATCH | SCH · ADR-070 · OD-029 |
| BR-LOT-003 | Expired lots cannot be sold | A lot is expired when `expiry_date` < business date (today in the business time zone, injected `Clock`): it is still sellable **on** its expiry date (ADR-055). An expired lot is refused at confirmation; the UI also warns early. A month-only expiry `MM/YYYY` means the last day of that month. | sale line | add line / confirm | `expiry_date` vs business date | SVC (+ UI) | LOT_EXPIRED | SRC FR-LOT-006 · ADR-055 |
| BR-LOT-004 | Blocked and recalled lots cannot be sold | Lots with status `blocked` or `recalled` are never offered or accepted for sale. (They may still be adjusted, counted or returned to the supplier.) | sale line | add line / confirm | `status = active` | SVC | LOT_BLOCKED / LOT_RECALLED | SRC FR-LOT-007 |
| BR-LOT-005 | FEFO proposal | For a sale quantity the system proposes lots in ascending `expiry_date`, then `id`, among sellable lots, splitting across lots as needed. Overriding the proposal: OD-010 (leading hypothesis: only with permission `sale.override_fefo` and a reason, audited). | sale line | add line / confirm | allocation order | SVC | FEFO_VIOLATION | SRC FR-LOT-004 · OD-010 |
| BR-LOT-006 | One lot per sale line | A sales line references exactly one lot; a quantity spanning several lots becomes several lines. | sale line | allocation | single `lot_id` | SVC | — | SCH |
| BR-LOT-007 | Lot required on stock-moving lines | At confirmation every line of a stock-moving document has `lot_id`, and `lot.product_id = line.product_id`. | sales line | confirm | presence, product match | SVC + DB composite FK `(lot_id, product_id) → lots(id, product_id)` (ADR-062) | LOT_REQUIRED / LOT_PRODUCT_MISMATCH | SRC FR-LOT-002 · SCH · ADR-062 |
| BR-LOT-008 | Lot recall | One operation: set the lot to `recalled`, store reason/user/time, write an audit entry, and return the buyers list — customer, phone, document number, date, quantity — including anonymous sales (customer unknown) and, per OD-002, delivery notes not yet invoiced. Until sales exist (Phase 7) the buyers lists are empty (ADR-068). | lot | recall action | permission `lot.recall`, reason | SVC (single transaction) | ACCESS_DENIED / NOT_FOUND | SRC FR-LOT-007 · SCH `recall_lot` · OD-002 |
| BR-LOT-009 | Lot status transitions | Stored status (ADR-059, ADR-060): `active → blocked` (`lot.block`, reason, audited), `blocked → active` (`lot.unblock`, reason, audited), `active / blocked → recalled` (`lot.recall`, reason, audited); `recalled` is terminal; every other request is refused. **Expired** (ADR-055) and **depleted** (quantity 0) are computed, never stored; the effective status shown is the first that applies of recalled, blocked, expired, depleted, active, so a depleted lot that receives stock again is active by itself. | lot | status change / read | transition table | SVC + DB (CHECK on stored values) | INVALID_STATE_TRANSITION | SCH + REC · ADR-059, ADR-060 |
| BR-LOT-010 | Configurable expiry thresholds | Alert thresholds come from setting `expiry_alert_days`: exactly three distinct positive integers, strictly decreasing (notice, warning, critical; default `60,30,15`; ADR-044). The alert level of a lot is computed from these values on each query, never hard-coded: expired when days left < 0, else critical / warning / notice when days left ≤ that threshold (inclusive, ADR-056). | expiry alerts | settings change / alert query | list of positive ints | SVC | VALIDATION_FAILED | SRC FR-LOT-005 |
| BR-LOT-011 | Lot identity immutable | `product`, `lot_number`, `expiry_date` of an existing lot cannot be edited; no endpoint edits them (Phase 3 offers none); a correction procedure, if one is needed, is decided with stock adjustments (Phase 4). | lot | update attempt | field set | SVC | DOCUMENT_IMMUTABLE | REC |
| BR-LOT-012 | No expired goods received silently | A receipt line whose expiry date is already past is rejected (or recorded as non-conforming per OD-014). | goods receipt line | receipt confirm | `expiry_date ≥ business date` | SVC | VALIDATION_FAILED | REC · OD-014 |

## 4. Stock and inventory counts (STOCK, INV)

| ID | Name | Description | Applies to | Trigger | Validation | Enforcement | Error | Basis |
|---|---|---|---|---|---|---|---|---|
| BR-STOCK-001 | No negative stock | `lots.quantity_on_hand` can never go below zero. Services pre-check under lock; the database `CHECK` is the last defence. | lot | any outgoing movement | `balance + delta ≥ 0` | DB (CHECK) + SVC | STOCK_INSUFFICIENT | SRC NFR-001 |
| BR-STOCK-002 | Every stock change is a movement | A balance changes only by inserting a `stock_movements` row and applying the same delta in the same transaction, through `StockService` (the only writer, ADR-088). | lot, movement | any stock operation | single gateway | SVC | — (violation = defect) | SRC FR-STOCK-001/002 · SCH · ADR-088 |
| BR-STOCK-003 | Movements are immutable | No `UPDATE`/`DELETE` of movements. Corrections are compensating movements. The application database account has only `SELECT, INSERT` on `stock_movements` (ADR-087); triggers stop every account (ADR-017). | stock_movements | update/delete attempt | — | DB (triggers) + privileges + SVC | INTERNAL_ERROR (DB error surfaced as defect) | SRC FR-STOCK-002 · SCH |
| BR-STOCK-004 | Movement is attributable | Each movement records operator, timestamp, type, signed base quantity, and (when applicable) the originating document. | stock_movements | insert | NOT NULL columns | DB + SVC | — | SRC FR-STOCK-002 |
| BR-STOCK-005 | Movement sign by type | `purchase_receipt`, `customer_return`, `opening_stock` > 0; `sale`, `supplier_return`, `adjustment_breakage`, `adjustment_expiry`, `adjustment_theft`, `adjustment_internal_use` < 0; `adjustment_inventory` ±. There is no `unit_conversion` type (ADR-061). | movement | insert | sign check | SVC | VALIDATION_FAILED | SCH + REC |
| BR-STOCK-006 | Adjustment reason mandatory | Every `adjustment_*` movement carries a non-blank reason. | movement | manual adjustment / inventory regularisation | non-blank | DB (CHECK) + API + SVC | ADJUSTMENT_REASON_REQUIRED | SRC FR-STOCK-005 |
| BR-STOCK-007 | Ledger–balance reconciliation | For every lot, `quantity_on_hand = Σ movements`. Verified by automated tests and by the integrity check (nightly at `integrity_check_time` and on demand): every run is stored, a mismatch logs a CRITICAL `LEDGER_MISMATCH` security event and shows a banner to `audit.read` holders until a clean run; the check never corrects (ADR-086). | lot, movements | nightly job / CI | equality | JOB + tests | LEDGER_MISMATCH (logged, alert) | REC (ADR-028, ADR-086) |
| BR-STOCK-008 | Adjustment categories | Manual adjustments are limited to breakage, expiry, theft and internal use, quantity > 0 in base units, allowed on blocked, recalled and expired lots, audited `STOCK_CORRECTION` (ADR-085); stock found/lost during a count uses the inventory workflow; initial stock uses opening stock. | adjustment | create adjustment | allowed types | SVC | VALIDATION_FAILED | SRC FR-STOCK-005 · SCH |
| BR-STOCK-009 | Thresholds configured per product | Min, max and reorder point are per product, `min ≤ max`. | product | create / update | bounds | DB (CHECK) + API | VALIDATION_FAILED | SRC FR-STOCK-003 |
| BR-STOCK-010 | Reorder proposal | A product not archived with `reorder_point` > 0 and sellable quantity ≤ `reorder_point` gets a `pending` proposal for `max_stock − sellable` (base units, rounded up to whole for a non-divisible product); without `max_stock` it gets none and is reported *thresholds incomplete*. At most one pending proposal per product, updated by each generation; a dismissed product gets no new proposal until a generation sees its sellable quantity above the reorder point. Generated daily at `reorder_job_time` and on demand. Supplier, open-order subtraction and conversion to a purchase order: Phase 5. | product | scheduled job / generate now | threshold; one pending | SVC + JOB + DB (unique pending) | — | SRC FR-STOCK-004 · ADR-076…079 |
| BR-STOCK-011 | Sellable-stock definition | Sellable quantity = Σ `quantity_on_hand` of lots with stored `status = active`, not expired (ADR-055) and quantity > 0. This single definition is used by POS, reorder and reports. | lot | read | predicate | SVC (one shared predicate) | — | SRC FR-LOT-006 · SCH `v_product_stock` |
| BR-STOCK-012 | Serialised stock operations | Operations touching the same lot are serialised by pessimistic row locks taken in ascending lot id (ADR-012). Lock wait timeout 5 s on application connections; a timeout is reported, not retried silently. | lot | any stock operation | lock order | SVC | CONCURRENT_MODIFICATION | REC (ADR-012) |
| BR-STOCK-013 | Stock levels | Per product: total = Σ lot balances; sellable per BR-STOCK-011; status *out* (sellable = 0), *low* (0 < sellable ≤ `min_stock`), *in stock* otherwise; *below reorder* when `reorder_point` > 0 and sellable ≤ `reorder_point`. Computed on read (ADR-080). | product | read | — | SVC | — | SRC FR-STOCK-003 · ADR-080 |
| BR-STOCK-014 | Opening stock | Allowed only while setting `opening_stock_open` is true (`OPENING_STOCK_CLOSED` otherwise; an inventory count is the path after go-live, ADR-082). Product, lot number, expiry date and unit cost (≥ 0, 4 decimals) required; quantity in a unit converted exactly; find-or-create by (product, lot number) under lock: other expiry → `LOT_EXPIRY_MISMATCH`, other unit cost → `COST_MISMATCH` (until OD-029), recalled lot → `LOT_RECALLED`; expired lots accepted. One `opening_stock` (+) movement, audited `OPENING_STOCK` (ADR-083, ADR-084). | lot, movement | opening-stock entry | setting, identity, cost | SVC + SETTING | OPENING_STOCK_CLOSED / LOT_EXPIRY_MISMATCH / LOT_RECALLED / VALIDATION_FAILED | SCH · ADR-082…084 |
| BR-INV-001 | Variance computation | Per line: `variance = counted − balance at the watermark` (the lot balance when the counted quantity was saved, ADR-072); `theoretical` (balance at count opening) is informational. | inventory line | count entry | `counted ≥ 0` | DB (generated, CHECK) | VALIDATION_FAILED | SRC FR-STOCK-006 |
| BR-INV-002 | Regularisation via movements | Closing/regularising a count generates one `adjustment_inventory` movement per line with non-zero variance, reason referencing the count, and flags the line `regularized`. | inventory | regularise | variance ≠ 0 | SVC | ADJUSTMENT_REASON_REQUIRED | SRC FR-STOCK-006 · SCH |
| BR-INV-003 | Count types and scope | `full` counts all lots with stock; `cycle` counts the lots with stock of one therapeutic category or of an explicit product list (ADR-073). Lots without stock or unknown to the system can be added during the count (ADR-074). | inventory | create | type, scope | SVC | VALIDATION_FAILED | SRC FR-STOCK-006 · ADR-073, ADR-074 |
| BR-INV-004 | Closed counts immutable | A `closed`/`cancelled` count cannot change; a line is regularised at most once. | inventory, line | update | status, flag | SVC | INVENTORY_NOT_OPEN / INVENTORY_LINE_ALREADY_REGULARIZED | REC |
| BR-INV-005 | Movements during an open count | Nothing is frozen: sales, receipts and adjustments continue. Movements after a line's watermark do not change its variance; regularisation posts the variance on the current balance (ADR-072). | inventory | movement during count | — | SVC | — | ADR-072 |
| BR-INV-006 | Regularisation permission and audit | Regularising a count requires permission `inventory.validate` and writes `STOCK_CORRECTION` audit entries with old/new quantities. | inventory | regularise | permission | SVC | ACCESS_DENIED | SRC FR-AUD-001 |
| BR-INV-007 | One open full count | At most one `full` count is `open` at a time. | inventory | create full count | no other open full count | SVC | INVENTORY_ALREADY_OPEN | REC |
| BR-INV-008 | Count line watermark | Saving a counted quantity locks the lot and stores its balance and last movement id (watermark) with user and time; saving again takes a new watermark. Regularisation posts the variance on the current balance; if that would make the balance negative it is refused (`STOCK_INSUFFICIENT`), nothing is posted and the count stays open (ADR-072, ADR-075). | inventory line | enter count / regularise | lock, watermark, balance ≥ 0 | SVC | STOCK_INSUFFICIENT / INVENTORY_NOT_OPEN | REC · ADR-072 |

## 5. Purchasing (PURCH, DOC)

| ID | Name | Description | Applies to | Trigger | Validation | Enforcement | Error | Basis |
|---|---|---|---|---|---|---|---|---|
| BR-PURCH-001 | Purchase-order lifecycle | `draft → validated → sent → partially_received → closed`; `cancelled` from `draft/validated/sent`; `closed` and `cancelled` are terminal. Forced close of a partially received order: OD-015. | purchase order | status action | transition table | SVC | INVALID_STATE_TRANSITION | SRC FR-PURCH-004 · SCH (cancelled) |
| BR-PURCH-002 | Server-computed amounts | `line_ht = round(qty × price × (1 − discount/100), 2)`; VAT per line rate; totals are sums. Rounding policy: OD-007. | purchase order | save / validate | recompute | SVC (+ DB generated `line_ht`) | — | SRC FR-PURCH-003 · OD-007 |
| BR-PURCH-003 | Only drafts are editable | After validation lines/prices/supplier are read-only; only receipt progress and status change. | purchase order | edit attempt | status = draft | SVC | DOCUMENT_IMMUTABLE | REC |
| BR-PURCH-004 | Receipt progress | `received_base` accumulates per PO line; the PO becomes `partially_received` after the first receipt and `closed` when all lines are fully received. Over-receipt and no-PO receipts: OD-015. | PO line, receipt | receipt confirm | quantity vs ordered | SVC | RECEIPT_QUANTITY_EXCEEDS_ORDER | SRC FR-PURCH-005 · OD-015 |
| BR-PURCH-005 | Receipt lines carry lot and expiry | Each receipt line has lot number, expiry date, quantity > 0, unit cost ≥ 0 (see BR-LOT-001). | receipt line | receipt confirm | required fields | API + SVC + DB | VALIDATION_FAILED | SRC FR-PURCH-005 |
| BR-PURCH-006 | Conformity control | Each line is checked against the PO (product, quantity, acceptable expiry) and flagged conforming / non-conforming. Stock treatment of non-conforming lines: OD-014. | receipt line | receipt confirm | flag | SVC | — | SRC FR-PURCH-005 · OD-014 |
| BR-PURCH-007 | Receipt is atomic | Receipt, lines, lot find-or-create, movements, balances, PO progress and numbering commit together or not at all. | goods receipt | confirm | single transaction | SVC | — | REC |
| BR-PURCH-008 | Supplier price history | A supplier price/discount record is appended per supplier×product when a price is confirmed (recommended moment: on receipt confirmation; not specified by the Spec). | supplier price history | receipt confirm | price ≥ 0, discount 0–100 | SVC | — | SRC FR-PURCH-002 + REC |
| BR-PURCH-009 | Supplier return | Requires a reason, a lot, a quantity ≤ lot balance; posts `supplier_return` (−) movements; requires permission `return.supplier.create`. | supplier return | create return | stock, reason | SVC | STOCK_INSUFFICIENT / VALIDATION_FAILED | SRC FR-PURCH-006 |
| BR-PURCH-010 | Reorder conversion | Converting a proposal creates a draft PO line (product, suggested quantity, supplier) and marks the proposal `converted`; each proposal converts once. | reorder proposal | convert action | status = pending | SVC | INVALID_STATE_TRANSITION | SCH · REC |
| BR-PURCH-011 | Inactive supplier | Inactive suppliers cannot receive new orders or receipts. | supplier | create PO / receipt | `is_active` | SVC | SUPPLIER_INACTIVE | REC |
| BR-DOC-001 | Strict unique document numbering | Every purchase order, goods receipt, sales document (per type), customer return, supplier return and credit note has a unique number generated by `NumberingService` inside the creating transaction; numbers are never reused or edited; cancelled documents keep their number. Assignment moment, format and key list: OD-017. | all numbered documents | document creation/confirmation | unique | DB (UNIQUE) + SVC | DOCUMENT_NUMBER_CONFLICT | SRC NFR-001 · SCH · OD-017 |

## 6. Sales and customers (SALE, CUST, RET)

| ID | Name | Description | Applies to | Trigger | Validation | Enforcement | Error | Basis |
|---|---|---|---|---|---|---|---|---|
| BR-SALE-001 | Document types and chain | Types: quote, delivery note, invoice, receipt (ticket), credit note. Allowed derivation: quote → delivery note, quote → invoice, delivery note → invoice, invoice (or receipt) → credit note, recorded in `parent_id`. | sales document | convert / create | allowed pairs | SVC | INVALID_STATE_TRANSITION | SCH |
| BR-SALE-002 | Stock-moving documents | Confirming a stock-moving document posts `sale` movements (−) atomically with FEFO lot assignment. Which types move stock and how double deduction is avoided: OD-002. | sales document | confirm | types per OD-002 | SVC | STOCK_INSUFFICIENT | SRC FR-STOCK-001 · OD-002 |
| BR-SALE-003 | Quote has no side effect | A quote never changes stock or financial balances. | quote | any | — | SVC | — | REC |
| BR-SALE-004 | Confirmed documents are immutable | After confirmation, lines, quantities, prices and customer cannot be edited. Allowed: cancellation (BR-SALE-014) or credit note. | sales document | edit attempt | status = draft | SVC | DOCUMENT_IMMUTABLE | REC · SRC (audit of cancellations) |
| BR-SALE-005 | Lots assigned at confirmation | Every line receives its lot at or before confirmation (FEFO auto-assignment if none chosen). | sales line | confirm | `lot_id` present | SVC | LOT_REQUIRED | SRC FR-LOT-002 |
| BR-SALE-006 | Price resolution | The server resolves unit price and discount by a precedence order (product price, unit price, customer product price, category discount, manual discount — order: OD-006). A manual override of the resolved price requires permission `price.edit` and is audited. | sales line | add / edit line | precedence | SVC | ACCESS_DENIED | SRC FR-SALE-005 · OD-006 |
| BR-SALE-007 | Discount bounds | Discount 0–100 % per line; a discount above the customer's entitled category discount requires permission `sale.discount`. | sales line | add / edit line | bounds | DB (CHECK) + SVC | VALIDATION_FAILED / ACCESS_DENIED | SCH + REC |
| BR-SALE-008 | Server-computed totals | Line amounts and document totals are computed by the server; VAT rate is snapshotted per line. Client totals are previews only. | sales document | save / confirm | recompute | SVC (+ DB generated line amounts) | — | SRC FR-SALE-003 · OD-007 |
| BR-SALE-009 | Cost snapshot | At confirmation `unit_cost := lot.unit_cost` for margin reporting. | sales line | confirm | — | SVC | — | SCH |
| BR-SALE-010 | Withdrawal-period warnings | Printed on documents for breeders for lines whose product has withdrawal periods: for the line's target species when given; otherwise per OD-028. | sales document (print) | render | — | SVC | — | SRC FR-PROD-005, FR-SALE-008 · OD-028 |
| BR-SALE-011 | Only active products | Archived products and non-sellable units are refused on sales lines. | sales line | add line | flags | SVC | PRODUCT_ARCHIVED / UNIT_NOT_SELLABLE | REC |
| BR-SALE-012 | Customer requirements | Anonymous (no customer) is allowed only for non-credit counter receipts; a credit sale requires a known, active customer. | sales document | save / confirm | customer presence | DB (CHECK) + SVC | CUSTOMER_REQUIRED_FOR_CREDIT / CUSTOMER_INACTIVE | SCH |
| BR-SALE-013 | Cash session link | Counter sales are linked to the cashier's open cash session. Whether a session is mandatory for every sale: OD-003. | sales document | confirm | open session | SVC | CASH_SESSION_REQUIRED | REC · OD-003 |
| BR-SALE-014 | Cancellation of a confirmed document | If allowed (OD-004): permission `sale.cancel`, mandatory reason, compensating stock movements, payment handling, audit `INVOICE_CANCEL`, number retained, `cancelled_by/at` set. | sales document | cancel action | permission, reason | SVC | ACCESS_DENIED / INVALID_STATE_TRANSITION | SRC FR-AUD-001 · OD-004 |
| BR-SALE-015 | Sufficient stock at confirmation | Each line's `quantity_base` ≤ its lot balance at lock time. (Specialisation of BR-STOCK-001.) | sales line | confirm | balance | DB (CHECK) + SVC | STOCK_INSUFFICIENT | SRC NFR-001 |
| BR-SALE-016 | Idempotent confirmation | Repeating a confirm request (same idempotency key, or already confirmed) never creates a second number, movement or payment. | sales document | confirm | key / status | SVC | IDEMPOTENCY_CONFLICT | REC |
| BR-CUST-001 | Customer typing and vet link | Types: breeder, veterinarian, other. `veterinarian_id` must reference a customer of type `veterinarian`. | customer | create / update | type check | SVC | VALIDATION_FAILED | SRC FR-SALE-004 · SCH |
| BR-CUST-002 | Credit limit validity | `credit_limit ≥ 0`. Meaning of `0`: OD-011. Changes are audited. | customer | create / update | bounds | DB (CHECK) + API | VALIDATION_FAILED | SRC FR-FIN-002 · OD-011 |
| BR-CUST-003 | No deletion with history | A customer with documents/payments is deactivated, never deleted. | customer | delete attempt | references | SVC | RESOURCE_IN_USE | REC |
| BR-CUST-004 | Inactive customer | No new sale or payment for an inactive customer. | customer | create document/payment | `is_active` | SVC | CUSTOMER_INACTIVE | REC |
| BR-RET-001 | Return bound to original sale | A customer return references confirmed sale lines; cumulative returned quantity per line ≤ sold quantity. | customer return | create | sum check | SVC | RETURN_QUANTITY_EXCEEDED | SCH (`sale_line_id`) + REC |
| BR-RET-002 | Disposition rules | `restock` only if the product is sealed/unopened and its lot is neither expired, blocked nor recalled; otherwise `discard`. | return line | create | flags | SVC (+ UI choice) | RETURN_NOT_RESTOCKABLE | SRC FR-SALE-006 |
| BR-RET-003 | Stock effect of a return | `restock` → `customer_return` movement (+) on the original lot (a depleted lot becomes active again if allowed by BR-LOT-009). `discard` → treatment per OD-005. | return line | create | — | SVC | — | SRC FR-SALE-006 · OD-005 |
| BR-RET-004 | Automatic credit note | Each customer return issues a credit note (own number) linked to the return and to the original document; amounts = returned quantity × original net unit price, VAT per original line. | customer return | create | — | SVC | — | SRC FR-SALE-007 |
| BR-RET-005 | Return is atomic | Return, lines, credit note, movements, numbering and audit commit together. | customer return | create | single transaction | SVC | — | REC |
| BR-RET-006 | Refund / settlement | Credit on a credit sale reduces the customer balance; for cash sales the refund mechanism (cash movement out) is decided in OD-005/OD-003. | credit note | issue | — | SVC | — | OD-005, OD-003 |

## 7. Finance (PAY, CASH)

| ID | Name | Description | Applies to | Trigger | Validation | Enforcement | Error | Basis |
|---|---|---|---|---|---|---|---|---|
| BR-PAY-001 | Payment validity | Amount > 0; method ∈ {cash, cheque, bank_transfer}; reference required for cheque and transfer; customer must be active. | customer / supplier payment | create | bounds, method | DB (CHECK) + API + SVC | VALIDATION_FAILED | SRC FR-FIN-006 + REC |
| BR-PAY-002 | Allocation limits | Σ allocations of a payment ≤ payment amount; each allocation ≤ the invoice's outstanding amount; one allocation row per (payment, invoice). | payment allocation | allocate | sums | DB (PK) + SVC | PAYMENT_ALLOCATION_EXCEEDED | SCH + REC |
| BR-PAY-003 | Financial history preserved | Payments, allocations, cash movements and supplier payments are never edited or deleted; errors are corrected by reversing entries with a reason, audited. | finance records | edit/delete attempt | — | SVC (+ DB triggers proposed SD-102) | DOCUMENT_IMMUTABLE | REC (brief) |
| BR-PAY-004 | Customer balance | The balance is derived, never stored; its exact definition (which invoices, credit notes and payments count) is OD-003. | customer | read | — | SVC / read model | — | SRC FR-FIN-002 · OD-003 |
| BR-PAY-005 | Credit-limit enforcement | Confirming a credit invoice requires `balance + invoice total ≤ credit_limit`, evaluated with the customer row locked. Meaning of limit `0` and overdue handling: OD-011. | credit invoice | confirm | balance check | SVC (via `CreditLimitPort`) | CREDIT_LIMIT_EXCEEDED | SRC FR-FIN-002 · OD-011 |
| BR-PAY-006 | Cash payment needs an open session | A cash-method payment recorded at the counter requires the cashier's open session. | payment | create | open session | SVC | CASH_SESSION_REQUIRED | REC |
| BR-PAY-007 | Account statement | Chronological list of invoices, credit notes and payments with running balance. | customer | read | — | SVC | — | SRC FR-FIN-003 |
| BR-PAY-008 | Supplier invoice | Unique per (supplier, invoice number); `due_date` defaults to `invoice_date + payment_terms_days`; total ≥ 0. | supplier invoice | create | uniqueness | DB (UNIQUE) + SVC | DUPLICATE_RESOURCE | SRC FR-FIN-004 |
| BR-PAY-009 | Supplier payment limits | A payment on an invoice ≤ its remaining amount; a payment without invoice is an advance. | supplier payment | create | remaining | SVC | PAYMENT_ALLOCATION_EXCEEDED | REC |
| BR-PAY-010 | Advances | Advances are recorded with `is_advance`; how they are later applied to invoices: OD-016. | supplier payment | create | — | SVC | — | SRC FR-FIN-005 · OD-016 |
| BR-PAY-011 | Supplier credit notes | Supplier credit notes (`is_credit_note`) reduce payables; linkage with supplier returns: OD-016. | supplier invoice | create | — | SVC | — | SRC FR-FIN-005 · OD-016 |
| BR-CASH-001 | One open session per cashier | A cashier cannot hold two open sessions. | cash session | open | uniqueness | DB (unique generated column) + SVC | CASH_SESSION_ALREADY_OPEN | SCH |
| BR-CASH-002 | Closing and variance | Closing requires the counted amount; the server computes expected; variance = counted − expected is stored. | cash session | close | counted ≥ 0 | SVC + DB (generated) | VALIDATION_FAILED | SRC FR-FIN-007 |
| BR-CASH-003 | Expected-cash definition | Expected = opening amount + Σ cash-method IN − Σ cash-method OUT of the session (cheques and transfers excluded). | cash session | close | — | SVC | — | REC |
| BR-CASH-004 | Closed session immutable | No posting to a closed session; late corrections are posted to the current session with a reason. | cash session | post | status = open | SVC | CASH_SESSION_CLOSED | REC |
| BR-CASH-005 | Cash movement validity | Amount > 0; direction ∈ {in, out}; method ∈ payment methods. | cash movement | create | bounds | DB (CHECK) | VALIDATION_FAILED | SCH |

## 8. Security, audit, reports, backup (SEC, AUDIT, RPT, BKP)

| ID | Name | Description | Applies to | Trigger | Validation | Enforcement | Error | Basis |
|---|---|---|---|---|---|---|---|---|
| BR-SEC-001 | Authentication required | Every endpoint except login (and optional health) requires authentication. | API | each request | valid credential | SVC (security filter) | AUTH_REQUIRED | SRC FR-SEC-001 |
| BR-SEC-002 | Permission-based authorisation | Every operation requires a permission granted through the user's role; checked in the backend. | API / services | each request | permission set | SVC | ACCESS_DENIED | SRC FR-SEC-002 |
| BR-SEC-003 | Password protection | Passwords stored only as bcrypt hashes (cost 12, ADR-039); never logged or returned. | user | create / change password | hash | SVC | — | SCH |
| BR-SEC-004 | Inactive user blocked | An inactive user cannot authenticate; existing credentials stop working at the next validation. | user | login / each request | `is_active` | SVC | AUTH_ACCOUNT_DISABLED | SCH |
| BR-SEC-005 | Brute-force protection | After `login_max_failures` failed logins for a username + IP within `login_window_minutes`, that pair is refused for `login_lockout_minutes` (settings; ADR-038). | login | failed attempt | counter | SVC | RATE_LIMITED | REC |
| BR-SEC-006 | Least-privilege database access | The application database account has no `UPDATE/DELETE/TRUNCATE` on `stock_movements` and `audit_log`; DDL uses a separate account. | database | deployment | grants | OPS | — | REC (ADR-008) |
| BR-AUDIT-001 | Sensitive actions audited | Audited: price changes, document cancellations, stock corrections (adjustments, count regularisation), lot block/unblock/recall, user and role changes, credit-limit changes, settings changes, backups/exports. (First three: SRC; remainder: REC.) | services | action | audit entry in same transaction | SVC | — | SRC FR-AUD-001 + REC |
| BR-AUDIT-002 | Audit entries immutable and complete | Each entry has user, action, entity, entity id, old/new values, client IP and timestamp; entries are append-only. | audit log | insert; update attempt | — | SVC + DB triggers (SD-102) | — | SRC FR-AUD-001 · SCH |
| BR-AUDIT-003 | Audit is transactional | The audit entry commits with the action; if it cannot be written the action fails. | services | sensitive action | single transaction | SVC | INTERNAL_ERROR | REC |
| BR-RPT-001 | Report scope | Revenue and margin use confirmed documents of the types decided in OD-002, **net of credit notes**; period boundaries use the business time zone. | reports | report query | filters | SVC / SQL | — | SRC FR-RPT-001 · OD-002 · OD-003 |
| BR-RPT-002 | Stock valuation | At cost: Σ lot quantity × lot unit cost. At sale price: Σ quantity × product sale price (per base unit). | reports | report query | — | SQL | — | SRC FR-RPT-002 |
| BR-RPT-003 | Report access | Reports are read-only and permission-gated; margin and cost figures need `report.margin`. | reports | request | permission | SVC | ACCESS_DENIED | REC |
| BR-BKP-001 | Automatic backup | The database is backed up automatically on a schedule; each run is recorded in `backup_log` (status, size, path). Mechanism, frequency, retention: OD-021. | backup | schedule | — | JOB / OPS | — | SRC FR-AUD-002 · OD-021 |
| BR-BKP-002 | Export | An authorised user (`backup.manage`) can export/download a backup; the action is audited. | backup | request | permission | SVC | ACCESS_DENIED | SRC FR-AUD-002 |

## 9. Rule statistics

| Family | Count | Family | Count |
|---|---|---|---|
| PROD | 7 | PAY | 11 |
| UNIT | 4 | CASH | 5 |
| LOT | 12 | SEC | 6 |
| STOCK | 12 | AUDIT | 3 |
| INV | 7 | RPT | 3 |
| PURCH | 11 | BKP | 2 |
| DOC | 1 | CUST | 4 |
| SALE | 16 | RET | 6 |
| **Total** | **110** | | |

Rules that depend on an Open Decision (`OD-…` in the Basis column) must be treated as **hypotheses** until the decision is recorded in `decisions.md`.
