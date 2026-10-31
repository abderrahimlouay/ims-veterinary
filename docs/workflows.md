# Business Workflows — IMS Vétérinaire

> **Status:** Documentation / Architecture Phase — Implementation Not Started
> **Last updated:** 2026-10-18

End-to-end workflows with actors, preconditions, steps, validations, database changes, transaction boundaries, failure cases and audit. Steps marked **⚠ OD-nnn** depend on an Open Decision and are written as the leading hypothesis only.

**Common conventions**

- "Transaction" = one `@Transactional` service method, READ COMMITTED, locks per ADR-012 (document sequence → lots by ascending id → customer row → other rows).
- Stock changes always go through `StockService` (BR-STOCK-002, ADR-088).
- Error codes are those of `api.md` §3. A failed transaction leaves **no** partial effect, including the numbering counter (ADR-011).
- Permission codes are those of `security.md` §4.

| # | Workflow | Section |
|---|---|---|
| W1 | Purchasing (order → receipt → invoice → payment) | §1 |
| W2 | Sales at the counter (FEFO) | §2 |
| W3 | Customer return | §3 |
| W4 | Supplier return | §4 |
| W5 | Physical inventory count | §5 |
| W6 | Lot recall | §6 |
| W7 | Manual stock adjustment | §7 |
| W8 | Customer payment and account statement | §8 |
| W9 | Cash session | §9 |
| W10 | Reorder proposal | §10 |
| W11 | Document conversion (quote → delivery note → invoice) | §11 |
| W12 | Expiry alerting | §12 |

---

## 1. W1 — Purchasing

```text
Supplier → Purchase Order → Goods Receipt → Lot Creation → Stock Update → Supplier Invoice → Supplier Payment
```

| Item | Content |
|---|---|
| **Actors** | Pharmacist (order, receipt), Accountant (invoice, payment), Administrator |
| **Preconditions** | Supplier active (BR-PURCH-011); products active (BR-PROD-004); permissions `purchase.create`, `receipt.create`, `supplierinvoice.manage`, `payment.supplier.create` |

### 1a. Create and validate a purchase order
1. User selects an active supplier; adds lines (product once per order, unit, quantity, unit price per ordered unit with 4 decimals, discount, VAT prefilled from the product). The price is prefilled from the supplier's latest `supplier_price_history` entry, else `products.purchase_price` (ADR-094, ADR-099, ADR-105). The PO number is assigned at creation (`PO-YYYY-NNNNNN`, ADR-097).
2. Server converts quantities to base units (BR-UNIT-002) and computes line and document totals HT/VAT/TTC per line, HALF_UP (BR-PURCH-002, ADR-093).
3. `Validate` → `validated`; `Send` → `sent` (printable PO in Phase 10). `Cancel` allowed from draft/validated/sent (audited `PO_CANCEL`). A `partially_received` order can be closed with a reason (audited `PO_CLOSE`, ADR-096).
4. **DB:** `purchase_orders`, `purchase_order_lines`; number via `document_sequences` (ADR-097).
5. **Validation:** BR-PURCH-001 (transitions), BR-PURCH-003 (only drafts editable).
6. **Failure:** invalid transition → `INVALID_STATE_TRANSITION`; edit after validation → `DOCUMENT_IMMUTABLE`.
7. **Audit:** `PO_CANCEL`, `PO_CLOSE` (ADR-105); other state changes logged at INFO.

### 1b. Goods receipt (lot creation and stock update) — *one transaction*
1. User opens the receipt for a `sent` or `partially_received` PO, or a free receipt without PO (ADR-096), and, per line, enters: quantity received (at most the outstanding quantity, only products of the order), **lot number**, **expiry date** (date or `MM/YYYY`), unit cost per base unit, conformity (BR-PURCH-005, BR-LOT-001, BR-LOT-012). The request carries an `Idempotency-Key` (ADR-104).
2. Server (inside the transaction):
   1. Checks the idempotency key (a repeat returns the first receipt), validates everything that needs no lock (supplier, order and its lines, products, lot numbers and expiry, quantities, costs, one line per lot), then assigns the receipt number (`GR-YYYY-NNNNNN`, ADR-097).
   2. For each line: converts to base unit; **finds or creates the lot** by (product, lot number) under lock (ascending lot id), refusing a different expiry date and a recalled lot; sets `supplier_id` on a new lot; the lot cost becomes the weighted average on the current balance, or the line cost when the balance is 0 (BR-LOT-002, ADR-070, ADR-098, ADR-105).
   3. Posts `purchase_receipt` (+quantity) through `StockService` (BR-STOCK-002); a blocked lot stays blocked.
   4. Locks the PO (after the lots, database.md §7), checks again what is outstanding, updates `purchase_order_lines.received_base`; sets PO to `partially_received` or `closed` (BR-PURCH-004; over-receipt refused, ADR-096).
   5. Non-conforming lines: the lot is blocked (audited `LOT_BLOCK`); if it already had stock, only with `confirmLotBlocking: true`, else `LOT_BLOCK_CONFIRMATION_REQUIRED` naming the quantity (ADR-095). An expired line is refused unless non-conforming (BR-LOT-012).
   6. Appends `supplier_price_history` when price or discount differs from the latest entry (BR-PURCH-008, ADR-100); stores the idempotency key (ADR-104).
3. Response: receipt detail + PO status. Reorder proposals are refreshed by the next generation (BR-STOCK-010).
- **One line per lot** (decided at the Phase 5 close): a lot is (product, lot number), so the same number for two products is two lots and both lines are received; the same lot twice in one receipt is refused on the second line with `DUPLICATE` and the message "add the quantities on one line". Two order lines never share a product (ADR-105 (e)), so two order lines cannot both claim one lot; one order line may be received in several lots.
- **DB changes:** `goods_receipts`, `goods_receipt_lines`, `lots`, `stock_movements`, `purchase_order_lines`, `purchase_orders`, `supplier_price_history`, `document_sequences`, `idempotency_keys`, `audit_log` (blocking).
- **Transaction:** atomic (BR-PURCH-007); lots locked in ascending id order.
- **Failure cases:**

| Condition | Result |
|---|---|
| lot number or expiry missing | `VALIDATION_FAILED` (field errors) |
| expiry already past (conforming line) | `VALIDATION_FAILED` `EXPIRED` (BR-LOT-012) |
| supplier inactive / product archived | `SUPPLIER_INACTIVE` / `PRODUCT_ARCHIVED` |
| received > outstanding | `RECEIPT_QUANTITY_EXCEEDS_ORDER` |
| product not on the order | `VALIDATION_FAILED` `NOT_ON_ORDER` |
| PO not in `sent`/`partially_received` | `INVALID_STATE_TRANSITION` |
| other expiry for an existing lot / recalled lot | `LOT_EXPIRY_MISMATCH` / `LOT_RECALLED` |
| non-conforming line into a lot with stock, not confirmed | `LOT_BLOCK_CONFIRMATION_REQUIRED` (quantity named) |
| same idempotency key, other body | `IDEMPOTENCY_CONFLICT` |
| DB error / deadlock | whole receipt rolled back; client may retry |

- **Audit:** the movement ledger is the trace of a receipt; lot blocking is audited `LOT_BLOCK`; a cancellation `RECEIPT_CANCEL` (W1d).

### 1d. Cancel a goods receipt (ADR-103)
1. A `receipt.cancel` holder cancels a **whole** confirmed receipt with a mandatory reason.
2. **Refused** with `RECEIPT_NOT_CANCELLABLE` (naming the lot and the later movement) if any of its lots has a movement after the receipt's own.
3. **One transaction:** one `receipt_cancellation` (−) movement per line through `StockService`; PO lines' received quantities and PO status rolled back; receipt `cancelled` (number kept); audited `RECEIPT_CANCEL`. A lot created by the receipt stays with a zero balance and its supplier is cleared; otherwise the lot cost before the receipt is restored, and a lot the receipt blocked gets its previous status back only if its status was not changed since (audited `LOT_UNBLOCK`); an order closed by a forced close stays closed; price-history entries of the receipt are marked cancelled (ADR-106).

### 1c. Supplier invoice and payment
1. Accountant records the supplier invoice (number, receipt link optional, dates, total TTC, credit-note flag). Due date defaults to `invoice_date + payment_terms_days` (BR-PAY-008).
2. Accountant records payments (method, amount, reference) against the invoice, or an **advance** without invoice (BR-PAY-009/010 **⚠ OD-016**). Cash payments create a `cash_movements` OUT row in the open cash session (BR-PAY-006).
3. Remaining payable = invoice amount (negative if credit note) − Σ payments.
- **DB:** `supplier_invoices`, `supplier_payments`, `cash_movements`.
- **Failure:** duplicate invoice number → `DUPLICATE_RESOURCE`; amount > remaining → `PAYMENT_ALLOCATION_EXCEEDED`; cash without open session → `CASH_SESSION_REQUIRED`.
- **Audit:** supplier payments are financial records, immutable (BR-PAY-003); reversals audited.

---

## 2. W2 — Sales at the counter

```text
Customer → Product Search → Lot Selection / FEFO → Sale → Stock Movement → Payment → Invoice/Receipt
```

| Item | Content |
|---|---|
| **Actors** | Seller (primary), Pharmacist (overrides, returns), customer (breeder, veterinarian, anonymous) |
| **Preconditions** | Logged in with `sale.create`; (counter cash sales) an open cash session for the cashier (BR-SALE-013, ADR-118: only when the payment includes cash); products active; stock exists |

### Steps
1. **Customer** (optional): select a customer (by name/phone) or continue anonymous. Customer data supplies category discount, preferential prices, credit limit, type.
2. **Product search** (NFR-002 < 1 s): scan barcode (keyboard-wedge, `Enter` ends) or search by name/DCI/category/species. Out-of-stock products offer **substitutes** with the same active ingredient (FR-PROD-004).
3. **Add line:** product, unit (box/bottle/dose…), quantity, target species (for breeders). Server returns the resolved unit price and discount (BR-SALE-006, ADR-107), the proposed lot(s) (FEFO, BR-LOT-005; another lot needs `sale.override_fefo` and a reason, ADR-120) and warnings (nearly expired lot; withdrawal period for any customer, every species when none is given, ADR-121).
   - A quantity that spans several lots is split into several lines (BR-LOT-006).
   - Expired / blocked / recalled lots are never proposed (BR-LOT-003/004).
4. **Review / discount:** manual discount beyond entitlement needs `sale.discount` (BR-SALE-007).
5. **Payment choice:** cash/cheque/transfer now, or **credit** (customer required, BR-SALE-012). An immediate sale is paid in full, several methods allowed; a credit sale takes no payment at confirmation (ADR-118).
6. **Confirm** (one transaction — idempotent, BR-SALE-016):
   1. Lock the document and take the number (BR-DOC-001; format ADR-097, keys and numbering at confirmation ADR-122), then lock lots in id order (BR-STOCK-012); re-validate status, expiry, quantity (BR-LOT-003/004, BR-SALE-015); order per `database.md` §7.
   2. If credit sale: lock customer, compute balance + uninvoiced credit delivery notes + total vs limit (BR-PAY-005, ADR-108, ADR-117; NULL limit = no amount check, 0 = refused; an invoice overdue beyond `credit_overdue_block_days`, when on, refuses also without a limit). A due date left empty defaults to today + the customer's payment terms.
   3. Snapshot `unit_cost` (BR-SALE-009); compute totals (BR-SALE-008).
   4. Post `sale` movements (−) and update balances; set lots `depleted` when 0 (BR-STOCK-002).
   5. Record payment and allocations, and a cash movement for the cash part (ADR-118).
   6. Mark document `confirmed`. Delivery notes, receipts and invoices not derived from a delivery note post movements; an invoice from a delivery note reuses its lots and posts none (ADR-117; commercial rules ADR-125).
7. **Output:** thermal ticket (receipt) or A4 invoice/delivery note PDF with withdrawal-period warnings for breeders (BR-SALE-010 **⚠ OD-028**, Phase 10).
- **DB changes:** `sales_documents`, `sales_document_lines`, `lots`, `stock_movements`, `document_sequences`, `customer_payments`/`payment_allocations`/`cash_movements` (ADR-118).
- **Failure cases:**

| Condition | Result |
|---|---|
| last units sold by another user meanwhile | `STOCK_INSUFFICIENT` (client refreshes lots) |
| lot expired/blocked/recalled | `LOT_EXPIRED` / `LOT_BLOCKED` / `LOT_RECALLED` |
| credit over limit | `CREDIT_LIMIT_EXCEEDED` |
| credit sale without customer | `CUSTOMER_REQUIRED_FOR_CREDIT` |
| archived product / non-sellable unit | `PRODUCT_ARCHIVED` / `UNIT_NOT_SELLABLE` |
| no open cash session (if required) | `CASH_SESSION_REQUIRED` |
| double submit (network retry) | same document returned, no duplicate (`Idempotency-Key`) |
| FEFO deviation without permission | `FEFO_VIOLATION` |

- **Audit:** price overrides (`PRICE_CHANGE`-type entry), FEFO overrides, discounts above entitlement `[REC]`. Cancellation: see W2b.

### 2b. Cancelling a confirmed document (ADR-119)
Permission `sale.cancel` + reason, whole document only. Refused (`SALE_NOT_CANCELLABLE`) if a return, credit note or derived document exists or its cash session is closed; those cases use a credit note (W3). One transaction: `sale_cancellation` (+) movement for every posted line, recorded payments reversed and the cash part posted OUT on the same session, status `cancelled`, `cancelled_by/at`, number retained, audit `INVOICE_CANCEL` with old/new values.

---

## 3. W3 — Customer return

```text
Customer Return → Validation → Product/Lot Verification → Restock or Discard → Stock Movement → Credit Note / Financial Adjustment
```

| Item | Content |
|---|---|
| **Actors** | Pharmacist (decides disposition), Seller (receives the customer), customer |
| **Preconditions** | Permission `return.customer.create`; the original confirmed sale document and line exist |

1. User finds the original document (by number or customer) and selects returned lines and quantities (BR-RET-001).
2. For each line the user states whether the product is **sealed** and the **reason**; the server checks lot state: restock is allowed only if sealed and the lot is not expired/blocked/recalled (BR-RET-002); otherwise only `discard`.
3. **Confirm** (one transaction):
   1. Numbers: return and credit note.
   2. `restock` → `customer_return` movement (+) on the original lot; a `depleted` lot becomes `active` if allowed (BR-RET-003, BR-LOT-009). `discard` → **⚠ OD-005** (no movement vs. traceability pair).
   3. Create credit note (`credit_note`, `parent_id` = original, amounts from original net prices, BR-RET-004).
   4. Settlement: credit-sale customer → reduces balance; cash/anonymous sale → refund via cash movement OUT **⚠ OD-005** (BR-RET-006).
   5. Audit entry `[REC]` (a return changes stock and money).
- **DB changes:** `customer_returns`, `customer_return_lines`, `sales_documents` (credit note) + lines, `stock_movements`, `lots`, `document_sequences`, `cash_movements`/`customer_payments` (if refunded).
- **Failure:** quantity beyond sold → `RETURN_QUANTITY_EXCEEDED`; restock not allowed → `RETURN_NOT_RESTOCKABLE`; original not confirmed → `INVALID_STATE_TRANSITION`.
- **Audit:** `RETURN_CREATE` `[REC]`.

---

## 4. W4 — Supplier return

```text
Supplier Return → Lot Selection → Stock Reduction → Supplier Credit
```

| Item | Content |
|---|---|
| **Actors** | Pharmacist, Accountant (credit follow-up) |
| **Preconditions** | `return.supplier.create`; lot has quantity; reason known (defective / non-conforming — Spec) |

1. User selects supplier, lots of that supplier (or without supplier) and quantities, and enters the reason (BR-PURCH-009, ADR-101). Active, blocked, recalled and expired lots may be returned.
2. **Confirm** (one transaction): number `SR-YYYY-NNNNNN`; `supplier_return` movements (−) through `StockService`; balances (a lot at 0 shows `depleted`); audited `RETURN_CREATE`.
3. **Supplier credit:** not in Phase 5 (ADR-101); the link to a supplier credit note is decided in Phase 9 (OD-016, SD-109).
- **DB changes:** `supplier_returns`, `supplier_return_lines`, `stock_movements`, `lots`, `document_sequences`, `audit_log`.
- **Failure:** quantity > lot balance → `STOCK_INSUFFICIENT`; missing reason or lot of another supplier → `VALIDATION_FAILED` (`OTHER_SUPPLIER`).
- **Audit:** `RETURN_CREATE` `[REC]`.

---

## 5. W5 — Physical inventory count

```text
Inventory Session → Count → Difference Detection → Validation → Stock Adjustment → Audit
```

| Item | Content |
|---|---|
| **Actors** | Pharmacist (runs count, validates), Administrator |
| **Preconditions** | Permission `inventory.count`; for `full` no other open full count (BR-INV-007) |

1. **Open session:** `full` (every lot with stock) or `cycle` (lots with stock of one category or an explicit product list, ADR-073). One line per lot with its balance at opening (`theoretical_qty`, informational).
2. **Count:** users enter `counted_qty` per line (barcode scan + lot selection). Saving a line locks the lot and records its balance and last movement id (**watermark**) with user and time; saving again takes a new watermark (ADR-072, BR-INV-008). Users may add a lot of the scope without a line, or a lot unknown to the system (product, lot number, expiry, unit cost, counted quantity; BR-LOT-001), created at regularisation (ADR-074).
3. **Difference detection:** `variance = counted − balance at the watermark` (BR-INV-001); each line also shows the movements on the lot since its watermark. Report lists variances by value (cost) for review.
4. **Validation** (permission `inventory.validate`): reviewer accepts lines to regularise (BR-INV-006).
5. **Regularise (one transaction):** the count, then every affected lot is locked in ascending id; lots found during the count are created after them, so in the same ascending order. Every lot's final balance (current balance + variance) is checked before anything is posted. For each accepted line with variance ≠ 0 → one `adjustment_inventory` movement of the variance on the **current** balance with reason "Inventory #id" (BR-INV-002); zero variance → no movement; line flagged `regularized`; session `closed` (ADR-075). Expired, blocked and recalled lots are regularised like any other (that is how unsellable stock is corrected). Any failure rolls everything back, created lots included, and the count stays open.
6. **Concurrency:** nothing is frozen; sales and receipts continue, and those after a line's watermark do not change its variance (BR-INV-005, ADR-072).
- **DB changes:** `inventories`, `inventory_lines`, `stock_movements`, `lots`, `audit_log`.
- **Failure:** closing a closed/cancelled count → `INVENTORY_NOT_OPEN`; naming a line already regularised → `INVENTORY_LINE_ALREADY_REGULARIZED`; a found lot number that now exists with another expiry → `LOT_EXPIRY_MISMATCH`; a variance that would make the current balance negative (sales after the watermark) → `STOCK_INSUFFICIENT`, nothing posted, count stays open.
- **Audit:** `STOCK_CORRECTION` per posted line, on its lot, with old/new quantity, in the same transaction (BR-AUDIT-001/003).

---

## 6. W6 — Lot recall

| Item | Content |
|---|---|
| **Actors** | Pharmacist |
| **Preconditions** | `lot.recall`; lot identified (manufacturer/authority notice) |

1. Pharmacist selects lot and enters the reason.
2. **One transaction:** lot → `recalled` (`blocked_reason/by/at`), audit `LOT_RECALL` (BR-LOT-008). `recalled` is final: nobody can unblock or un-recall the lot (ADR-060).
3. System returns the **buyers list**: customer, phone, document number/date, quantity — including anonymous sales (customer unknown) and un-invoiced delivery notes (ADR-117). Until sales exist (Phase 7) the lists are empty and the screen says so (ADR-068).
4. List is printable/exportable; remaining stock of the lot is no longer sellable and can be returned to the supplier (W4) or discarded by adjustment (W7).
- **Failure:** unknown lot → `NOT_FOUND`; already recalled → idempotent success; no permission → `ACCESS_DENIED`.

## 7. W7 — Manual stock adjustment

1. Pharmacist chooses lot (blocked, recalled and expired lots included, ADR-060), type (breakage / expiry / theft / internal use), quantity (outgoing, base unit) and **mandatory reason** (BR-STOCK-006, BR-STOCK-008, ADR-085).
2. **One transaction:** movement `adjustment_*` (−), balance update, audit `STOCK_CORRECTION` with old/new quantity.
3. **Failure:** blank reason → `ADJUSTMENT_REASON_REQUIRED`; quantity > balance → `STOCK_INSUFFICIENT`.

## 8. W8 — Customer payment and account statement

1. Accountant/Seller selects the customer and records a payment (method, amount, reference). Cash payments require an open session (BR-PAY-006).
2. The payment is **allocated** to one or several invoices (default: oldest first `[REC]`), each ≤ outstanding (BR-PAY-002); any remainder stays unallocated (advance on account) and lowers the balance (ADR-118).
3. **One transaction:** `customer_payments`, `payment_allocations`, `cash_movements` IN (cash).
4. **Account statement:** invoices, credit notes and payments in date order with running balance (BR-PAY-007; balance definition ADR-118), printable A4.
5. **Corrections:** a mistaken payment is reversed by a counter-entry with reason, never edited (BR-PAY-003); audited.
- **Failure:** allocation > outstanding → `PAYMENT_ALLOCATION_EXCEEDED`; customer inactive → `CUSTOMER_INACTIVE`.

## 9. W9 — Cash session

1. **Open:** cashier declares the opening amount; refused if the cashier already has an open session (BR-CASH-001).
2. **During the day:** sales and payments in cash create IN/OUT movements linked to the session; other methods are recorded but excluded from expected cash (BR-CASH-003).
3. **Close:** cashier enters the **counted amount**; the server computes expected and **variance** (BR-CASH-002); session becomes immutable (BR-CASH-004). A report is printed.
- **Failure:** opening twice → `CASH_SESSION_ALREADY_OPEN`; posting to closed → `CASH_SESSION_CLOSED`.
- **Audit:** non-zero variance at closing logged `[REC]`.

## 10. W10 — Reorder proposal

1. **Generation** (daily at `reorder_job_time`, or *Generate now*, ADR-078): for every product not archived with `reorder_point` > 0 and sellable ≤ `reorder_point` (ADR-076), create or update its one pending proposal with `max_stock − sellable` (ADR-077, ADR-079); products without `max_stock` are reported *thresholds incomplete*, those with `max_stock` ≤ `reorder_point` *thresholds inconsistent* (no proposal for either); a dismissed product is skipped until a generation sees it above the reorder point.
2. Pharmacist reviews proposals and **dismisses** them, or (from Phase 5) selects several and **converts** them: one new draft purchase order per chosen supplier, one line per proposal (→ W1a); proposals become `converted` with their supplier (ADR-102). From Phase 5 the generation subtracts the outstanding quantity of validated, sent and partially received orders.

## 10a. W10a — Opening stock (Phase 4)

1. While `opening_stock_open` is true (ADR-082), a `stock.adjust` holder enters product, lot number, expiry (date or `MM/YYYY`), quantity in a unit and unit cost (prefilled from the purchase price).
2. **One transaction:** find-or-create the lot under lock (other expiry → `LOT_EXPIRY_MISMATCH`, the lot cost becomes the weighted average (ADR-098), recalled → `LOT_RECALLED`), `opening_stock` (+) movement, balance update, audit `OPENING_STOCK` (ADR-083, ADR-084).
3. **Failure:** period closed → `OPENING_STOCK_CLOSED`, the screen points to an inventory count.

## 11. W11 — Document conversion

`Quote → Delivery note → Invoice`, and `Invoice/Receipt → Credit note` (BR-SALE-001). Conversion copies lines into a new draft with `parent_id`; an invoice from a confirmed delivery note posts no second movement (ADR-117; commercial rules ADR-125). A quote converts while `confirmed` or `draft`, as often as asked; its lines keep the quoted price and VAT and take their lots by FEFO at conversion (`STOCK_INSUFFICIENT` without enough stock); its price validity period is not specified by the Spec. A delivery note converts once confirmed and only if it has no non-cancelled invoice, a draft included; abandoning that draft frees it. That invoice's lines and its customer and credit status cannot change.

## 12. W12 — Expiry alerting

1. **On-demand computation** (every lot list, alert list and dashboard query): for each lot with stock, `daysLeft = expiry_date − business date` (Africa/Algiers, injected `Clock`); level *expired* when `daysLeft < 0` (sellable on the expiry date itself, ADR-055), else *critical* / *warning* / *notice* when `daysLeft ≤` the configured threshold (default 15 / 30 / 60, inclusive; BR-LOT-010, ADR-056). No scheduled job is needed.
2. UI shows visual alerts (badges/banner on dashboard, lot list, POS). No email/SMS (Spec: visual notifications).
3. `expired` is never stored (ADR-059); expired lots are unsellable because the rule is evaluated whenever a lot is used (BR-LOT-003).
