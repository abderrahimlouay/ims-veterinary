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
1. User selects supplier; adds lines (product, unit, quantity, unit price, discount, VAT). The price may be pre-filled from `supplier_price_history`. **⚠ OD-007** (rounding).
2. Server converts quantities to base units (BR-UNIT-002) and computes line and document totals HT/VAT/TTC (BR-PURCH-002).
3. `Validate` → `validated`; `Send` → `sent` (printable PO, `printing`). `Cancel` allowed from draft/validated/sent.
4. **DB:** `purchase_orders`, `purchase_order_lines`; number via `document_sequences` **⚠ OD-017** (assignment moment).
5. **Validation:** BR-PURCH-001 (transitions), BR-PURCH-003 (only drafts editable).
6. **Failure:** invalid transition → `INVALID_STATE_TRANSITION`; edit after validation → `DOCUMENT_IMMUTABLE`.
7. **Audit:** none required by the Spec; state changes logged at INFO `[REC]`.

### 1b. Goods receipt (lot creation and stock update) — *one transaction*
1. User opens the receipt for a PO (or without PO **⚠ OD-015**) and, per line, enters: quantity received, **lot number**, **expiry date**, unit cost, conformity (BR-PURCH-005, BR-LOT-001, BR-LOT-012).
2. Server (inside the transaction):
   1. Assigns the receipt number (`NumberingService`).
   2. For each line: converts to base unit; **finds or creates the lot** by (product, lot number) under lock, refusing a different expiry date (BR-LOT-002, ADR-070; cost policy **⚠ OD-029**).
   3. Inserts `stock_movements` (`purchase_receipt`, +quantity) and applies the delta to `lots.quantity_on_hand` (BR-STOCK-002); sets lot `active` (or `depleted → active`).
   4. Updates `purchase_order_lines.received_base`; sets PO to `partially_received` or `closed` (BR-PURCH-004; over-receipt **⚠ OD-015**).
   5. Non-conforming lines: **⚠ OD-014** (stock treatment).
   6. Appends `supplier_price_history` when the price is confirmed (BR-PURCH-008).
3. Response: receipt detail + PO status; reorder proposals for received products may be closed/refreshed (after commit, BR-STOCK-010).
- **DB changes:** `goods_receipts`, `goods_receipt_lines`, `lots`, `stock_movements`, `purchase_order_lines`, `purchase_orders`, `supplier_price_history`, `document_sequences`.
- **Transaction:** atomic (BR-PURCH-007); lots locked in ascending id order.
- **Failure cases:**

| Condition | Result |
|---|---|
| lot number or expiry missing | `VALIDATION_FAILED` (field errors) |
| expiry already past | `VALIDATION_FAILED` (BR-LOT-012) |
| supplier inactive / product archived | `SUPPLIER_INACTIVE` / `PRODUCT_ARCHIVED` |
| received > ordered (if forbidden) | `RECEIPT_QUANTITY_EXCEEDS_ORDER` |
| PO not in `sent`/`partially_received` | `INVALID_STATE_TRANSITION` |
| DB error / deadlock | whole receipt rolled back; client may retry |

- **Audit:** stock-increasing receipts are not "sensitive" in the Spec; the movement ledger is the trace. A receipt correction (OD-004-like) would be audited `[REC]`.

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
| **Preconditions** | Logged in with `sale.create`; (counter cash sales) an open cash session for the cashier (BR-SALE-013 **⚠ OD-003**); products active; stock exists |

### Steps
1. **Customer** (optional): select a customer (by name/phone) or continue anonymous. Customer data supplies category discount, preferential prices, credit limit, type.
2. **Product search** (NFR-002 < 1 s): scan barcode (keyboard-wedge, `Enter` ends) or search by name/DCI/category/species. Out-of-stock products offer **substitutes** with the same active ingredient (FR-PROD-004).
3. **Add line:** product, unit (box/bottle/dose…), quantity, target species (for breeders). Server returns the resolved unit price and discount (BR-SALE-006 **⚠ OD-006**), the proposed lot(s) (FEFO, BR-LOT-005 **⚠ OD-010**) and warnings (nearly expired lot, withdrawal period).
   - A quantity that spans several lots is split into several lines (BR-LOT-006).
   - Expired / blocked / recalled lots are never proposed (BR-LOT-003/004).
4. **Review / discount:** manual discount beyond entitlement needs `sale.discount` (BR-SALE-007).
5. **Payment choice:** cash/cheque/transfer now, or **credit** (customer required, BR-SALE-012). **⚠ OD-003** (how immediate payments are recorded).
6. **Confirm** (one transaction — idempotent, BR-SALE-016):
   1. Lock lots in id order (BR-STOCK-012); re-validate status, expiry, quantity (BR-LOT-003/004, BR-SALE-015).
   2. If credit sale: lock customer, compute balance + total vs limit (BR-PAY-005 **⚠ OD-011**).
   3. Assign number (BR-DOC-001 **⚠ OD-017**); snapshot `unit_cost` (BR-SALE-009); compute totals (BR-SALE-008).
   4. Post `sale` movements (−) and update balances; set lots `depleted` when 0 (BR-STOCK-002).
   5. Record payment/allocations/cash movement per OD-003.
   6. Mark document `confirmed`; **which document types move stock: ⚠ OD-002**.
7. **Output:** thermal ticket (receipt) or A4 invoice/delivery note PDF with withdrawal-period warnings for breeders (BR-SALE-010 **⚠ OD-028**).
- **DB changes:** `sales_documents`, `sales_document_lines`, `lots`, `stock_movements`, `document_sequences`, `customer_payments`/`payment_allocations`/`cash_movements` (OD-003).
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

### 2b. Cancelling a confirmed document (⚠ OD-004)
Permission `sale.cancel` + reason. Compensating stock movements for every posted line, handling of any recorded payment (reversal), status `cancelled`, `cancelled_by/at`, number retained, audit `INVOICE_CANCEL` with old/new values. If OD-004 chooses "credit note only", this action does not exist and W3 is used.

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
   4. Settlement: credit-sale customer → reduces balance; cash/anonymous sale → refund via cash movement OUT **⚠ OD-005 / OD-003** (BR-RET-006).
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

1. User selects supplier, lots and quantities, and enters the reason (BR-PURCH-009). Blocked or recalled lots may be returned.
2. **Confirm** (one transaction): number; `supplier_return` movements (−); balances; lot `depleted` when 0.
3. **Supplier credit:** the Source schema has no link to the supplier credit note; the accountant records a `supplier_invoices` row with `is_credit_note` manually **⚠ OD-016 / SD-109**.
- **DB changes:** `supplier_returns`, `supplier_return_lines`, `stock_movements`, `lots`, `document_sequences`.
- **Failure:** quantity > lot balance → `STOCK_INSUFFICIENT`; missing reason → `VALIDATION_FAILED`.
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
5. **Regularise (one transaction, lots locked in ascending id):** for each accepted line with variance ≠ 0 → `adjustment_inventory` movement of the variance on the **current** balance with reason "Inventory #id" (BR-INV-002), found lots created first; line flagged `regularized`; session `closed` (ADR-075). Any failure rolls everything back and the count stays open.
6. **Concurrency:** nothing is frozen; sales and receipts continue, and those after a line's watermark do not change its variance (BR-INV-005, ADR-072).
- **DB changes:** `inventories`, `inventory_lines`, `stock_movements`, `lots`, `audit_log`.
- **Failure:** closing a closed/cancelled count → `INVENTORY_NOT_OPEN`; line already regularised → `INVENTORY_LINE_ALREADY_REGULARIZED`; a variance that would make the current balance negative (sales after the watermark) → `STOCK_INSUFFICIENT`, nothing posted, count stays open.
- **Audit:** `STOCK_CORRECTION` per regularised line with old/new quantity (BR-AUDIT-001).

---

## 6. W6 — Lot recall

| Item | Content |
|---|---|
| **Actors** | Pharmacist |
| **Preconditions** | `lot.recall`; lot identified (manufacturer/authority notice) |

1. Pharmacist selects lot and enters the reason.
2. **One transaction:** lot → `recalled` (`blocked_reason/by/at`), audit `LOT_RECALL` (BR-LOT-008). `recalled` is final: nobody can unblock or un-recall the lot (ADR-060).
3. System returns the **buyers list**: customer, phone, document number/date, quantity — including anonymous sales (customer unknown) and, per **⚠ OD-002**, un-invoiced delivery notes. Until sales exist (Phase 7) the lists are empty and the screen says so (ADR-068).
4. List is printable/exportable; remaining stock of the lot is no longer sellable and can be returned to the supplier (W4) or discarded by adjustment (W7).
- **Failure:** unknown lot → `NOT_FOUND`; already recalled → idempotent success; no permission → `ACCESS_DENIED`.

## 7. W7 — Manual stock adjustment

1. Pharmacist chooses lot (blocked, recalled and expired lots included, ADR-060), type (breakage / expiry / theft / internal use), quantity (outgoing, base unit) and **mandatory reason** (BR-STOCK-006, BR-STOCK-008, ADR-085).
2. **One transaction:** movement `adjustment_*` (−), balance update, audit `STOCK_CORRECTION` with old/new quantity.
3. **Failure:** blank reason → `ADJUSTMENT_REASON_REQUIRED`; quantity > balance → `STOCK_INSUFFICIENT`.

## 8. W8 — Customer payment and account statement

1. Accountant/Seller selects the customer and records a payment (method, amount, reference). Cash payments require an open session (BR-PAY-006).
2. The payment is **allocated** to one or several invoices (default: oldest first `[REC]`), each ≤ outstanding (BR-PAY-002); any remainder stays unallocated (advance on account) **⚠ OD-003**.
3. **One transaction:** `customer_payments`, `payment_allocations`, `cash_movements` IN (cash).
4. **Account statement:** invoices, credit notes and payments in date order with running balance (BR-PAY-007; balance definition **⚠ OD-003**), printable A4.
5. **Corrections:** a mistaken payment is reversed by a counter-entry with reason, never edited (BR-PAY-003); audited.
- **Failure:** allocation > outstanding → `PAYMENT_ALLOCATION_EXCEEDED`; customer inactive → `CUSTOMER_INACTIVE`.

## 9. W9 — Cash session

1. **Open:** cashier declares the opening amount; refused if the cashier already has an open session (BR-CASH-001).
2. **During the day:** sales and payments in cash create IN/OUT movements linked to the session; other methods are recorded but excluded from expected cash (BR-CASH-003).
3. **Close:** cashier enters the **counted amount**; the server computes expected and **variance** (BR-CASH-002); session becomes immutable (BR-CASH-004). A report is printed.
- **Failure:** opening twice → `CASH_SESSION_ALREADY_OPEN`; posting to closed → `CASH_SESSION_CLOSED`.
- **Audit:** non-zero variance at closing logged `[REC]`.

## 10. W10 — Reorder proposal

1. **Generation** (daily at `reorder_job_time`, or *Generate now*, ADR-078): for every product not archived with `reorder_point` > 0 and sellable ≤ `reorder_point` (ADR-076), create or update its one pending proposal with `max_stock − sellable` (ADR-077, ADR-079); products without `max_stock` are reported *thresholds incomplete*; a dismissed product is skipped until a generation sees it above the reorder point.
2. Pharmacist reviews proposals and **dismisses** them, or (from Phase 5, with suppliers and purchase orders) **converts** one to a draft purchase order (→ W1a). Proposal status `converted`/`dismissed`.

## 10a. W10a — Opening stock (Phase 4)

1. While `opening_stock_open` is true (ADR-082), a `stock.adjust` holder enters product, lot number, expiry (date or `MM/YYYY`), quantity in a unit and unit cost (prefilled from the purchase price).
2. **One transaction:** find-or-create the lot under lock (other expiry → `LOT_EXPIRY_MISMATCH`, other cost → `COST_MISMATCH`, recalled → `LOT_RECALLED`), `opening_stock` (+) movement, balance update, audit `OPENING_STOCK` (ADR-083, ADR-084).
3. **Failure:** period closed → `OPENING_STOCK_CLOSED`, the screen points to an inventory count.

## 11. W11 — Document conversion

`Quote → Delivery note → Invoice`, and `Invoice/Receipt → Credit note` (BR-SALE-001). Conversion copies lines into a new draft with `parent_id`; **stock-moving rule and double-deduction prevention: ⚠ OD-002**. A quote converts only while `confirmed` or `draft` per policy `[REC]`; its price validity period is not specified by the Spec.

## 12. W12 — Expiry alerting

1. **On-demand computation** (every lot list, alert list and dashboard query): for each lot with stock, `daysLeft = expiry_date − business date` (Africa/Algiers, injected `Clock`); level *expired* when `daysLeft < 0` (sellable on the expiry date itself, ADR-055), else *critical* / *warning* / *notice* when `daysLeft ≤` the configured threshold (default 15 / 30 / 60, inclusive; BR-LOT-010, ADR-056). No scheduled job is needed.
2. UI shows visual alerts (badges/banner on dashboard, lot list, POS). No email/SMS (Spec: visual notifications).
3. `expired` is never stored (ADR-059); expired lots are unsellable because the rule is evaluated whenever a lot is used (BR-LOT-003).
