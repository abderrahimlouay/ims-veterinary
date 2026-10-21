# REST API Design — IMS Vétérinaire

> **Status:** Documentation / Architecture Phase — Implementation Not Started
> **Scope:** architectural contract only. No controller code. The OpenAPI file generated later from the code must be diffed against this document.
> **Last updated:** 2026-10-18

Tags: `SRC` Spec · `SCH` Source Schema · `REC` recommendation · `OD-nnn` open decision (contract part marked **[OPEN]** must not be implemented before the decision).

## 1. Conventions

| Topic | Convention |
|---|---|
| Base path | `/api` (ADR-026); no version segment while the bundled SPA is the only consumer |
| Format | `application/json; charset=utf-8`; PDFs as `application/pdf` |
| Naming | resources plural kebab-case; JSON properties **camelCase**; enums as the lower-snake-case strings of the database (`purchase_receipt`) |
| Authentication | session cookie `SESSION` + `X-XSRF-TOKEN` header on state-changing requests, login included (ADR-036). The CSRF token arrives in the readable `XSRF-TOKEN` cookie on any response (the SPA calls `GET /api/auth/me` first). Every endpoint except `POST /api/auth/login` (and `GET /actuator/health`) requires a session (BR-SEC-001) |
| Authorisation | one **permission** per endpoint (`security.md` §4), enforced on the service method (BR-SEC-002) |
| Methods | `GET` read · `POST` create or **action** (`/confirm`, `/cancel`, `/recall`…) · `PUT` full replace of a mutable resource (with `version`) · `DELETE` only where physical removal is allowed (drafts, child rows). **No hard delete** of master/transactional data |
| Success codes | `200` read/update/action · `201` create (+ `Location`) · `204` no body |
| Decimals | **JSON strings** (`"12.500"`) for money, quantities, percentages (ADR-021); scale: money 2, quantity 3, cost 4, percent 2 |
| Dates/times | Instants: ISO-8601 UTC `2026-10-18T14:20:00.123Z`; business dates: `YYYY-MM-DD`. Business-calendar logic uses the configured business time zone (ADR-010) |
| Identifiers | numeric `id` (JSON number); human numbers (`INV-2026-000123`) in `number` |
| Pagination | `?page=0&size=20` (size ≤ 100); response envelope `{ "content": [...], "page": { "number": 0, "size": 20, "totalElements": 134, "totalPages": 7 } }` |
| Sorting | `?sort=field,asc` (repeatable); only whitelisted fields; unknown → `VALIDATION_FAILED` |
| Filtering | explicit query parameters per endpoint (`q`, `status`, `from`, `to`, …); `q` = free text |
| Concurrency | mutable resources return `version`; `PUT` must send it; mismatch → `409 CONCURRENT_MODIFICATION` (ADR-027) |
| Idempotency | `POST` of numbered documents/money records accept `Idempotency-Key` (UUID); same key + same body returns the first result; same key + different body → `409 IDEMPOTENCY_CONFLICT` (BR-SALE-016) |
| Server-owned fields | `id`, `number`, `status` (except via actions), totals, `quantityBase`, `unitCost`, `createdBy`, `createdAt` are **ignored** if sent |
| Language | error `detail` text is for humans; clients switch on `code` only (ADR-020) |
| Rate limiting | `429 RATE_LIMITED` with `Retry-After` |
| Tracing | every response carries `X-Trace-Id`; errors repeat it as `traceId` |

## 2. Standard error response (RFC 9457 Problem Details + business code)

```json
{
  "type": "https://ims.example/problems/stock-insufficient",
  "title": "Insufficient stock",
  "status": 422,
  "code": "STOCK_INSUFFICIENT",
  "detail": "Lot L2407A of 'Amoxicilline 500 mg' has 9.000 tablet available; 12.000 requested.",
  "instance": "/api/sales/4512/confirm",
  "traceId": "9f1c2b…",
  "errors": [],
  "context": { "lotId": 88, "availableBase": "9.000", "requestedBase": "12.000" }
}
```

- `errors[]` is used for `VALIDATION_FAILED`: `{ "field": "lines[0].lotNumber", "code": "REQUIRED", "message": "…" }`.
- `context` carries machine-readable facts (ids, quantities) so the UI can refresh the right lot.
- No stack traces, SQL or internal class names are ever returned.
- *As implemented (Phase 0):* `type` is `urn:ims:problem:<kebab-code>` (e.g. `urn:ims:problem:stock-insufficient`; the example host above is illustrative); `title` is the code in words; `errors` and `context` are always present (empty when unused); field-error `code` is `REQUIRED` for `@NotNull`/`@NotBlank`/`@NotEmpty`, otherwise the constraint name in upper snake case (`POSITIVE`, `SIZE`, `PATTERN`…). Framework errors map by status: 400 → `VALIDATION_FAILED`, 404 → `NOT_FOUND`, 405 → `METHOD_NOT_ALLOWED`, 415 → `MEDIA_TYPE_NOT_SUPPORTED`, any other 4xx → `VALIDATION_FAILED`, 5xx → `INTERNAL_ERROR`; the HTTP status is always the code's catalogue status. The catalogue below is checked against the `ErrorCode` enum by `ErrorCatalogueTest`.

## 3. Error code catalogue

| Code | HTTP | Meaning | Related rules |
|---|---|---|---|
| `VALIDATION_FAILED` | 400 | malformed or invalid input (field errors) | all API validation |
| `AUTH_REQUIRED` | 401 | no/invalid/expired credential | BR-SEC-001 |
| `AUTH_INVALID_CREDENTIALS` | 401 | login failed (generic) | BR-SEC-001 |
| `AUTH_ACCOUNT_DISABLED` | 403 | user inactive | BR-SEC-004 |
| `ACCESS_DENIED` | 403 | authenticated but lacks permission, or missing/invalid CSRF token | BR-SEC-002, ADR-036 |
| `PASSWORD_CHANGE_REQUIRED` | 403 | temporary password must be changed first | ADR-039 |
| `NOT_FOUND` | 404 | resource does not exist | — |
| `METHOD_NOT_ALLOWED` | 405 | HTTP method not supported on this path (added Phase 0) | — |
| `MEDIA_TYPE_NOT_SUPPORTED` | 415 | request `Content-Type` not supported (added Phase 0) | — |
| `DUPLICATE_RESOURCE` | 409 | uniqueness violated (generic) | BR-UNIT-001, BR-PAY-008 |
| `PRODUCT_SKU_DUPLICATE` | 409 | SKU exists | BR-PROD-001 |
| `BARCODE_DUPLICATE` | 409 | barcode exists | BR-PROD-002 |
| `DOCUMENT_NUMBER_CONFLICT` | 409 | number collision (should not occur) | BR-DOC-001 |
| `CONCURRENT_MODIFICATION` | 409 | stale `version` or lock timeout | BR-STOCK-012, ADR-027 |
| `INVALID_STATE_TRANSITION` | 409 | action not allowed in current status | BR-LOT-009, BR-PURCH-001/010, BR-SALE-001/014 |
| `DOCUMENT_IMMUTABLE` | 409 | document/record can no longer be edited | BR-PURCH-003, BR-SALE-004, BR-PAY-003, BR-LOT-011 |
| `RESOURCE_IN_USE` | 409 | cannot delete: referenced | BR-CUST-003 |
| `IDEMPOTENCY_CONFLICT` | 409 | key reused with different request | BR-SALE-016 |
| `INVENTORY_NOT_OPEN` | 409 | count is closed/cancelled | BR-INV-004 |
| `INVENTORY_ALREADY_OPEN` | 409 | another full count is open | BR-INV-007 |
| `INVENTORY_LINE_ALREADY_REGULARIZED` | 409 | line regularised | BR-INV-004 |
| `CASH_SESSION_ALREADY_OPEN` | 409 | cashier has an open session | BR-CASH-001 |
| `CASH_SESSION_REQUIRED` | 409 | operation needs an open session | BR-SALE-013, BR-PAY-006 |
| `CASH_SESSION_CLOSED` | 409 | session closed | BR-CASH-004 |
| `PRODUCT_ARCHIVED` | 422 | product archived | BR-PROD-004, BR-SALE-011 |
| `UNIT_NOT_SELLABLE` | 422 | unit not sellable | BR-UNIT-003 |
| `UNIT_FACTOR_LOCKED` | 422 | unit factor in use | BR-UNIT-004 |
| `LOT_EXPIRED` | 422 | lot expired | BR-LOT-003 |
| `LOT_BLOCKED` | 422 | lot blocked | BR-LOT-004 |
| `LOT_RECALLED` | 422 | lot recalled | BR-LOT-004 |
| `LOT_REQUIRED` | 422 | line has no lot at confirmation | BR-LOT-007, BR-SALE-005 |
| `LOT_PRODUCT_MISMATCH` | 422 | lot belongs to another product | BR-LOT-007 |
| `FEFO_VIOLATION` | 422 | FEFO order not respected without permission | BR-LOT-005 |
| `STOCK_INSUFFICIENT` | 422 | not enough stock | BR-STOCK-001, BR-SALE-015, BR-PURCH-009 |
| `ADJUSTMENT_REASON_REQUIRED` | 422 | reason missing | BR-STOCK-006, BR-INV-002 |
| `RECEIPT_QUANTITY_EXCEEDS_ORDER` | 422 | over-receipt refused (if OD-015 forbids) | BR-PURCH-004 |
| `SUPPLIER_INACTIVE` | 422 | supplier inactive | BR-PURCH-011 |
| `CUSTOMER_INACTIVE` | 422 | customer inactive | BR-CUST-004, BR-SALE-012 |
| `CUSTOMER_REQUIRED_FOR_CREDIT` | 422 | credit sale without customer | BR-SALE-012 |
| `CREDIT_LIMIT_EXCEEDED` | 422 | limit would be exceeded | BR-PAY-005 |
| `RETURN_QUANTITY_EXCEEDED` | 422 | returned > sold | BR-RET-001 |
| `RETURN_NOT_RESTOCKABLE` | 422 | restock not allowed | BR-RET-002 |
| `PAYMENT_ALLOCATION_EXCEEDED` | 422 | allocation exceeds payment/outstanding | BR-PAY-002, BR-PAY-009 |
| `RATE_LIMITED` | 429 | too many attempts | BR-SEC-005 |
| `LEDGER_MISMATCH` | 500 | balance ≠ Σ movements (integrity check) | BR-STOCK-007 |
| `INTERNAL_ERROR` | 500 | unexpected failure | BR-STOCK-003, BR-AUDIT-003 |

Status policy: **400** malformed input · **401/403** identity/permission · **404** missing · **409** conflict with current state/uniqueness/concurrency · **422** syntactically valid request that violates a business rule · **429** throttled · **500** defect.

## 4. Endpoint catalogue

Format: **Method · Path · Purpose · Permission · Contract (request → response; notable errors)**. Unless stated, list endpoints are paginated; `GET /…/{id}` returns the detail DTO with `version`; unknown id → `NOT_FOUND`; missing permission → `ACCESS_DENIED`. `●` marks endpoints detailed in §5.

### 4.1 `/api/auth`

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| POST | `/api/auth/login` ● | authenticate | public | `{username, password}` → user + role + permissions + `mustChangePassword`; sets the session cookie (ADR-036); `AUTH_INVALID_CREDENTIALS`, `AUTH_ACCOUNT_DISABLED`, `RATE_LIMITED` |
| POST | `/api/auth/logout` | delete the session | authenticated | → 204 |
| GET | `/api/auth/me` | current user, role, permission codes | authenticated | → `CurrentUser {user: {id, username, fullName, email, role}, permissions: [code…], mustChangePassword}` (same body as login) |
| POST | `/api/auth/change-password` | change own password (also clears `mustChangePassword`) | authenticated | `{currentPassword, newPassword}` → 204 and a new session id; `VALIDATION_FAILED` with field errors: `currentPassword`/`INVALID`, `newPassword`/`TOO_SHORT` or `TOO_COMMON` (a wrong current password is not a 401, which would end the SPA session) |

### 4.2 `/api/users`, `/api/roles`, `/api/permissions`

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| GET | `/api/users` | list (filters `q`, `roleId`, `active`) | `user.read` | page of `UserSummary` |
| POST | `/api/users` | create | `user.manage` | `{username, fullName, email?, roleId, initialPassword}` → 201 `UserDetail`; `DUPLICATE_RESOURCE` |
| GET/PUT | `/api/users/{id}` | view / update (no password) | `user.read` / `user.manage` | `UserDetail` |
| POST | `/api/users/{id}/deactivate` · `/activate` | toggle active | `user.manage` | → 200; `INVALID_STATE_TRANSITION` for self-deactivation or administrator lock-out (ADR-042) |
| POST | `/api/users/{id}/reset-password` | issue temporary password | `user.manage` | → one-time temporary password |
| GET | `/api/roles`, `/api/roles/{id}`, `/api/permissions` | view | `user.read` | |
| PUT | `/api/roles/{id}/permissions` | replace permission set | `role.manage` | `{permissionCodes[]}`; audited; `INVALID_STATE_TRANSITION` on administrator lock-out (ADR-042) |

### 4.3 `/api/products` and reference data

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| GET | `/api/products` ● | search/list: `q` (name/SKU/DCI), `barcode`, `categoryId`, `manufacturerId`, `ingredientId`, `speciesId` **[OPEN OD-018]**, `includeArchived`, `inStockOnly` | `product.read` | page of `ProductSummary` (with `sellableQtyBase`, `stockStatus`) |
| GET | `/api/products/by-barcode/{code}` | scan lookup | `product.read` | `ProductSummary` + matched unit **[OPEN OD-019]**; `NOT_FOUND` |
| POST | `/api/products` | create | `product.create` | `ProductWriteRequest` → 201 `ProductDetail`; `PRODUCT_SKU_DUPLICATE`, `VALIDATION_FAILED` |
| GET | `/api/products/{id}` | detail (units, barcodes, ingredients, withdrawal periods) | `product.read` | `ProductDetail` |
| PUT | `/api/products/{id}` | update (price fields need `price.edit` and are audited) | `product.update` (+ `price.edit`) | `ProductWriteRequest` + `version`; `CONCURRENT_MODIFICATION` |
| POST | `/api/products/{id}/archive` · `/restore` | archive / restore | `product.archive` | → 200 |
| GET | `/api/products/{id}/substitutes` | same active ingredient products with sellable stock | `product.read` | list of `ProductSummary` |
| GET | `/api/products/{id}/lots` | lots in FEFO order | `lot.read` | list of `LotSummary` |
| GET/POST | `/api/products/{id}/units` | list / add packaging unit | `product.read` / `product.update` | `{name, factorToBase, salePrice?, sellable}`; `DUPLICATE_RESOURCE` |
| PUT | `/api/products/{id}/units/{unitId}` | edit unit | `product.update` | `UNIT_FACTOR_LOCKED` when used |
| POST/DELETE | `/api/products/{id}/barcodes[/{barcodeId}]` | add / remove barcode | `product.update` | `{code, codeType, unitId?}`; `BARCODE_DUPLICATE` |
| PUT | `/api/products/{id}/ingredients` | replace ingredient set | `product.update` | `[{ingredientId, strength}]` |
| PUT | `/api/products/{id}/withdrawal-periods` | replace set | `product.update` | `[{speciesId, meatDays?, milkDays?, eggsDays?}]` |
| GET/POST/PUT | `/api/categories` | therapeutic categories | `product.read` / `reference.manage` | `{name}` ; delete refused if used (`RESOURCE_IN_USE`) |
| GET/POST/PUT | `/api/manufacturers` | manufacturers | same | `{name, country?}` |
| GET/POST/PUT | `/api/dosage-forms`, `/api/active-ingredients`, `/api/animal-species` | reference lists | same | `{name}` |

### 4.4 `/api/lots`

> There is **no** `POST /api/lots`: lots are created only by goods receipts, opening stock, or inventory-found entries (BR-LOT-001).

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| GET | `/api/lots` | list: `productId`, `status`, `expiringWithinDays`, `hasStock`, `q` | `lot.read` | page of `LotSummary` |
| GET | `/api/lots/{id}` | detail | `lot.read` | `LotDetail` |
| GET | `/api/lots/fefo` ● | FEFO allocation preview (does **not** reserve) | `lot.read` | `?productId&quantity&unitId?` → allocation list |
| GET | `/api/lots/expiry-alerts` | lots grouped by alert level (thresholds from settings) | `lot.read` | `{expired[], critical[], warning[], notice[], thresholds}` |
| GET | `/api/lots/{id}/movements` | movement history of the lot | `stock.read` | page of `MovementEntry` |
| POST | `/api/lots/{id}/block` · `/unblock` | block / unblock | `lot.block` / `lot.unblock` | `{reason}` → `LotDetail`; `INVALID_STATE_TRANSITION` |
| POST | `/api/lots/{id}/recall` ● | recall + buyers list | `lot.recall` | `{reason}` → `RecallReport` |
| GET | `/api/lots/{id}/recall-report` | re-read buyers list | `lot.recall` | `RecallReport` (+ `?format=pdf`) |

### 4.5 `/api/inventory` (stock, adjustments, counts, reorder)

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| GET | `/api/inventory/stock` | product stock levels: `belowMin`, `belowReorder`, `categoryId`, `q` | `stock.read` | page of `StockLevel` (sellable vs total, thresholds) |
| GET | `/api/inventory/movements` | ledger: `productId`, `lotId`, `type`, `userId`, `from`, `to` | `stock.read` | page of `MovementEntry` |
| POST | `/api/inventory/adjustments` ● | manual adjustment | `stock.adjust` | `{lotId, type, quantity, reason}` → `MovementEntry`; `ADJUSTMENT_REASON_REQUIRED`, `STOCK_INSUFFICIENT` |
| POST | `/api/inventory/opening-stock` | initial stock entry | `stock.adjust` | `{productId, lotNumber, expiryDate, quantity, unitId?, unitCost}` → `LotDetail` |
| GET | `/api/inventory/reorder-proposals` | list: `status` | `reorder.read` | page of `ProposalEntry` |
| POST | `/api/inventory/reorder-proposals/generate` | run planner now **[OPEN OD-013]** | `reorder.manage` | → list created |
| POST | `/api/inventory/reorder-proposals/{id}/convert` · `/dismiss` | convert to draft PO / dismiss | `reorder.manage` | convert → `PurchaseOrderDetail` (draft); `INVALID_STATE_TRANSITION` |
| GET | `/api/inventory/counts` | list counts | `inventory.count` | page of `CountSummary` |
| POST | `/api/inventory/counts` | open a count | `inventory.count` | `{type, scope? [OPEN OD-012], notes}` → 201 `CountDetail`; `INVENTORY_ALREADY_OPEN` |
| GET | `/api/inventory/counts/{id}` | detail with lines/variances | `inventory.count` | `CountDetail` |
| PUT | `/api/inventory/counts/{id}/lines/{lineId}` | enter counted quantity | `inventory.count` | `{countedQty}`; `INVENTORY_NOT_OPEN` |
| POST | `/api/inventory/counts/{id}/lines` | add a lot found but not listed **[OPEN OD-012]** | `inventory.count` | lot identity + counted quantity |
| POST | `/api/inventory/counts/{id}/regularize` ● | validate & post adjustments, close | `inventory.validate` | `{lineIds?}` → `CountDetail`; `INVENTORY_NOT_OPEN`, `STOCK_INSUFFICIENT` |
| POST | `/api/inventory/counts/{id}/cancel` | cancel | `inventory.validate` | → 200 |
| GET | `/api/inventory/integrity-check` | ledger reconciliation (BR-STOCK-007) | `audit.read` | `{checked, mismatches[]}` `[REC]` |

### 4.6 `/api/suppliers`, `/api/supplier-invoices`

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| GET/POST | `/api/suppliers` | list / create | `supplier.read` / `supplier.manage` | `SupplierDetail` fields: name, kind (`laboratory`/`wholesaler`), taxId, phone, email, address, paymentTermsDays |
| GET/PUT | `/api/suppliers/{id}` | view / update | `supplier.read` / `supplier.manage` | with `version` |
| POST | `/api/suppliers/{id}/deactivate` · `/activate` | toggle | `supplier.manage` | |
| GET/POST | `/api/suppliers/{id}/prices` | price & discount history / add entry | `supplier.read` / `supplier.manage` | `{productId, unitPrice, discountPct, validFrom}` |
| GET | `/api/suppliers/{id}/payables` | open invoices and remaining | `payment.read` | list with `remaining` |
| GET/POST | `/api/supplier-invoices` | list / record | `supplierinvoice.manage` | `{supplierId, invoiceNumber, receiptId?, invoiceDate, dueDate?, totalTtc, creditNote}`; `DUPLICATE_RESOURCE` |
| GET | `/api/supplier-invoices/{id}` | detail with payments | `supplierinvoice.manage` | |

### 4.7 `/api/purchase-orders`

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| GET | `/api/purchase-orders` | list: `status`, `supplierId`, `from`, `to` | `purchase.read` | page |
| POST | `/api/purchase-orders` | create draft | `purchase.create` | `{supplierId, expectedDate?, lines[{productId, unitId?, quantity, unitPrice, discountPct, vatRate?}]}` → 201 (server totals); `SUPPLIER_INACTIVE`, `PRODUCT_ARCHIVED` |
| GET | `/api/purchase-orders/{id}` | detail | `purchase.read` | lines include `receivedBase` |
| PUT | `/api/purchase-orders/{id}` | edit draft (full replace of lines) | `purchase.create` | `DOCUMENT_IMMUTABLE` if not draft |
| POST | `/api/purchase-orders/{id}/validate` · `/send` · `/cancel` | status actions | `purchase.validate` | `INVALID_STATE_TRANSITION` |
| POST | `/api/purchase-orders/{id}/close` | force close partially received **[OPEN OD-015]** | `purchase.validate` | `{reason}` |
| GET | `/api/purchase-orders/{id}/pdf` | printable PO | `purchase.read` | `application/pdf` |

### 4.8 `/api/goods-receipts`

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| GET | `/api/goods-receipts` | list: `supplierId`, `purchaseOrderId`, `from`, `to` | `receipt.read` | page |
| GET | `/api/goods-receipts/{id}` | detail with lots and movements | `receipt.read` | |
| POST | `/api/goods-receipts` ● | confirm a receipt (creates/feeds lots, posts stock) | `receipt.create` | see §5.4 |

### 4.9 `/api/customers`, `/api/customer-categories`

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| GET | `/api/customers` | search: `q`, `type`, `wilaya`, `commune`, `active` | `customer.read` | page of `CustomerSummary` |
| POST | `/api/customers` | create | `customer.manage` | `{name, customerType, categoryId?, farmingType?, wilaya?, commune?, address?, phone?, veterinarianId?}` |
| GET/PUT | `/api/customers/{id}` | view / update (credit limit excluded) | `customer.read` / `customer.manage` | with `version` |
| PUT | `/api/customers/{id}/credit-limit` | change credit limit (audited) | `customer.credit.manage` | `{creditLimit}` — semantics OD-011 |
| POST | `/api/customers/{id}/deactivate` · `/activate` | toggle | `customer.manage` | |
| GET | `/api/customers/{id}/balance` | balance, limit, available credit | `customer.read` | definition **[OPEN OD-003]** |
| GET | `/api/customers/{id}/statement` | account statement `?from&to` (+ `format=pdf`) | `statement.read` | chronological lines + running balance |
| GET | `/api/customers/{id}/open-invoices` | invoices with outstanding amount (for allocation) | `payment.read` | |
| GET | `/api/customers/{id}/sales` | sales history | `sale.read` | |
| GET/PUT | `/api/customers/{id}/prices` | per-product prices | `customer.read` / `customer.price.manage` | `[{productId, unitPrice}]` — per base unit; audited |
| GET/POST/PUT | `/api/customer-categories` | categories with default discount | `customer.read` / `customer.price.manage` | `{name, discountPct}` |

### 4.10 `/api/sales`

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| GET | `/api/sales` | list: `docType`, `status`, `customerId`, `number`, `from`, `to` | `sale.read` | page |
| POST | `/api/sales` | create a **draft** document | `sale.create` | `{docType, customerId?, parentId?, creditSale?, dueDate?, notes?}` → 201 |
| GET | `/api/sales/{id}` | detail with lines, totals, warnings | `sale.read` | |
| POST | `/api/sales/{id}/lines` ● | add line; server resolves unit, price, FEFO lot(s), warnings | `sale.create` | see §5.5 → updated document |
| PUT/DELETE | `/api/sales/{id}/lines/{lineId}` | edit / remove draft line | `sale.create` | `DOCUMENT_IMMUTABLE` if not draft |
| PUT | `/api/sales/{id}` | edit draft header | `sale.create` | `version` |
| DELETE | `/api/sales/{id}` | abandon a draft | `sale.create` | only drafts (numbering **OD-017**) |
| POST | `/api/sales/{id}/confirm` ● | confirm; stock-moving per OD-002 | `sale.create` | see §5.6 |
| POST | `/api/sales/{id}/cancel` **[OPEN OD-004]** | cancel a confirmed document | `sale.cancel` | `{reason}` |
| POST | `/api/sales/{id}/convert` | derive quote→DN→invoice | `sale.create` | `{targetType}` → new draft with `parentId` |
| GET | `/api/sales/{id}/pdf` | `?format=a4\|ticket` | `sale.read` | `application/pdf` (OD-020) |
| GET | `/api/sales/{id}/withdrawal-warnings` | warnings printed on the document | `sale.read` | per BR-SALE-010 |

### 4.11 `/api/returns`

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| GET | `/api/returns/customer` · `/{id}` | list / detail | `return.read` | |
| POST | `/api/returns/customer` ● | create customer return + credit note | `return.customer.create` | see §5.8 |
| GET | `/api/returns/supplier` · `/{id}` | list / detail | `return.read` | |
| POST | `/api/returns/supplier` | create supplier return | `return.supplier.create` | `{supplierId, reason, lines[{lotId, quantity, unitId?}]}`; `STOCK_INSUFFICIENT` |

### 4.12 `/api/payments`, `/api/cash-sessions`

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| POST | `/api/payments/customers` ● | record customer payment + allocations | `payment.customer.create` | see §5.9 |
| GET | `/api/payments/customers` · `/{id}` | list / detail | `payment.read` | |
| POST | `/api/payments/customers/{id}/reverse` | reverse (counter-entry) | `payment.reverse` | `{reason}`; audited |
| POST | `/api/payments/suppliers` | record supplier payment (or advance) | `payment.supplier.create` | `{supplierId, invoiceId?, method, amount, reference?, advance}`; `PAYMENT_ALLOCATION_EXCEEDED` |
| GET | `/api/payments/suppliers` · `/{id}` | list / detail | `payment.read` | |
| POST | `/api/payments/suppliers/{id}/reverse` | reverse | `payment.reverse` | |
| POST | `/api/cash-sessions/open` | open own session | `cash.open` | `{openingAmount}` → 201; `CASH_SESSION_ALREADY_OPEN` |
| GET | `/api/cash-sessions/current` | own open session with running expected amount | `cash.open` | |
| POST | `/api/cash-sessions/{id}/close` ● | close with counted amount | `cash.open` (own) / `cash.manage` | `{countedAmount}` → `CashSessionDetail` (variance) |
| GET | `/api/cash-sessions` · `/{id}` | all sessions | `cash.manage` | |
| POST | `/api/cash-sessions/{id}/movements` | manual cash in/out | `cash.manage` | `{direction, method, amount, label}`; `CASH_SESSION_CLOSED` |
| GET | `/api/cash-sessions/{id}/report` | session report (`format=pdf`) | `cash.manage` | |

### 4.13 `/api/reports` (read-only; `?format=json\|csv\|pdf`)

| Method | Path | Purpose | Permission |
|---|---|---|---|
| GET | `/api/reports/revenue` | revenue; `from`, `to`, `groupBy=day\|month\|category\|product` | `report.sales` |
| GET | `/api/reports/margin` | gross margin; `groupBy=category\|product` | `report.margin` |
| GET | `/api/reports/stock-valuation` | `basis=cost\|sale_price` | `report.stock` |
| GET | `/api/reports/sales-statistics` | by period, breeder, zone (`wilaya`, `commune`) | `report.sales` |
| GET | `/api/reports/customer-balances` | receivables ageing | `report.finance` |
| GET | `/api/reports/supplier-payables` | payables by due date | `report.finance` |
| GET | `/api/reports/cash-sessions` | variances over a period | `report.finance` |
| GET | `/api/reports/lot-traceability` | `lotId` → upstream receipt and downstream sales | `lot.recall` |

Scope of revenue/margin per OD-002 / OD-003 and BR-RPT-001 (net of credit notes).

### 4.14 Administration

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| GET | `/api/audit-logs` | filters `entity`, `entityId`, `userId`, `action`, `from`, `to` (business dates, both inclusive, business time zone); newest first | `audit.read` | page of `AuditEntry {id, userId, action, entity, entityId, oldValues, newValues, ipAddress, createdAt}` (read-only; values are JSON objects) |
| GET | `/api/settings/public` | settings needed by the UI | authenticated | → `{expiryAlertDays: [60,30,15], defaultVatRate: "9.00", currency, locale, timeZone}` (no security settings) |
| GET | `/api/settings`, `/api/settings/{key}` | list / read settings | `settings.manage` | `[SettingView {key, value, description, editable, updatedAt}]`; unknown key → `NOT_FOUND` |
| PUT | `/api/settings/{key}` | change one setting (audited `SETTING_CHANGE` when the value changes) | `settings.manage` | `{value}` → `SettingView` with the canonical value; `VALIDATION_FAILED` field `value`: `INVALID_FORMAT` or `READ_ONLY` (currency, time zone). No batch update (ADR-044) |
| GET | `/api/backups` | backup log | `backup.manage` | page |
| POST | `/api/backups` | run a backup now **[OPEN OD-021]** | `backup.manage` | → `BackupRecord` |
| GET | `/api/backups/{id}/download` | download (audited) | `backup.manage` | stream |

## 5. Detailed contracts (critical endpoints)

Template: **Method · Endpoint · Purpose · Authentication · Required permission · Request · Response · Validation · Possible errors.**

### 5.1 Login
- **POST `/api/auth/login`** — authenticate. **Auth:** none. **Permission:** public.
- **Request:** `{ "username": "string(≤60)", "password": "string" }`
- **Response 200:** `{ "user": {id, username, fullName, email, role}, "permissions": ["sale.create", …], "mustChangePassword": false }` and a new session cookie (ADR-036). The request carries the CSRF header.
- **Validation:** both fields non-blank. **Errors:** `AUTH_INVALID_CREDENTIALS` (401, same detail for unknown user and wrong password), `AUTH_ACCOUNT_DISABLED` (403, only after the right password), `RATE_LIMITED` (429 with `Retry-After`), `ACCESS_DENIED` (403, missing CSRF header).

### 5.2 Product search
- **GET `/api/products?q=amox&categoryId=1&page=0&size=20`** — fast search for POS/catalogue. **Permission:** `product.read`.
- **Response:** page of `{ id, sku, commercialName, baseUnit, category, manufacturer, salePrice, sellableQtyBase, totalQtyBase, stockStatus: "in_stock|low|out", nearestExpiry, archived }`. `stockStatus`: out = sellable 0, low = ≤ `min_stock`.
- **Validation:** `size ≤ 100`; sort whitelist. **Performance:** target < 1 s (NFR-002, OD-018). **Errors:** `VALIDATION_FAILED`.

### 5.3 FEFO preview
- **GET `/api/lots/fefo?productId=12&quantity=3&unitId=7`** — **Permission:** `lot.read`.
- **Response:** `{ "requestedBase": "15.000", "allocations": [ {"lotId": 88, "lotNumber": "L2407A", "expiryDate": "2027-03-31", "quantityBase": "10.000", "available": "10.000", "alertLevel": "notice"}, {"lotId": 91, …, "quantityBase": "5.000"} ], "shortfallBase": "0.000" }`
- **Rules:** only sellable lots (BR-STOCK-011); order `expiry_date, id`; **informational** — stock is re-validated at confirmation. **Errors:** `NOT_FOUND`, `UNIT_NOT_SELLABLE`, `PRODUCT_ARCHIVED`.

### 5.4 Confirm goods receipt
- **POST `/api/goods-receipts`** — **Permission:** `receipt.create`. **Header:** `Idempotency-Key`.
- **Request:**
```json
{ "supplierId": 3, "purchaseOrderId": 41, "supplierDocRef": "BL-88213", "notes": "",
  "lines": [ { "poLineId": 120, "productId": 12, "unitId": 7, "quantity": "10",
               "lotNumber": "L2407A", "expiryDate": "2027-03-31",
               "unitCost": "0.3500", "conforming": true } ] }
```
- **Response 201:** `{ id, number, supplier, purchaseOrder: {id, status}, lines: [{lotId, lotNumber, expiryDate, quantityBase, unitCost, movementId, conforming}] }`
- **Validation:** `lotNumber` non-blank and `expiryDate` present (BR-LOT-001/BR-PURCH-005); quantity > 0; expiry not past (BR-LOT-012); `quantityBase = quantity × unit factor` computed server-side.
- **Errors:** `VALIDATION_FAILED`, `SUPPLIER_INACTIVE`, `PRODUCT_ARCHIVED`, `INVALID_STATE_TRANSITION` (PO not receivable), `RECEIPT_QUANTITY_EXCEEDS_ORDER`, `NOT_FOUND`, `IDEMPOTENCY_CONFLICT`.
- **Transaction:** all-or-nothing (BR-PURCH-007).

### 5.5 Add sales line (POS)
- **POST `/api/sales/{id}/lines`** — **Permission:** `sale.create`.
- **Request:** `{ "productId": 12, "unitId": 7, "quantity": "3", "targetSpeciesId": 1, "lotId": null, "manualDiscountPct": null, "manualUnitPrice": null }` (`lotId` only with `sale.override_fefo` **[OPEN OD-010]**; manual price needs `price.edit`; manual discount above entitlement needs `sale.discount`).
- **Response 200:** the document with recomputed lines and totals; each line `{id, productId, lotId, lotNumber, expiryDate, unitId, quantity, quantityBase, unitPrice, discountPct, vatRate, lineHt, lineTtc, warnings[]}`; a multi-lot quantity yields several lines.
- **Warnings (non-blocking):** `LOT_NEAR_EXPIRY`, `WITHDRAWAL_PERIOD` (meat/milk/eggs days), `LOW_STOCK_AFTER_SALE`.
- **Errors:** `DOCUMENT_IMMUTABLE`, `PRODUCT_ARCHIVED`, `UNIT_NOT_SELLABLE`, `STOCK_INSUFFICIENT`, `LOT_EXPIRED`, `LOT_BLOCKED`, `LOT_RECALLED`, `FEFO_VIOLATION`, `ACCESS_DENIED`.

### 5.6 Confirm sale
- **POST `/api/sales/{id}/confirm`** — **Permission:** `sale.create`. **Header:** `Idempotency-Key` (recommended, enforced for POS).
- **Request:** `{ "payments": [ {"method": "cash", "amount": "45.00", "reference": null} ] }` — payment payload **[OPEN OD-003]**; for credit sales `payments` may be empty.
- **Response 200:** `{ id, number, status: "confirmed", totals: {ht, discount, vat, ttc}, lines[…], payments[…], customerBalance?, documents: { pdfA4: "/api/sales/{id}/pdf?format=a4", ticket: "…" } }`
- **Server steps (one transaction):** lock lots (id order) → re-validate → credit check → number → post movements → payment records → `confirmed`.
- **Validation:** document is a draft; at least one line; every line has a lot; totals recomputed server-side.
- **Errors:** `STOCK_INSUFFICIENT`, `LOT_EXPIRED`, `LOT_BLOCKED`, `LOT_RECALLED`, `LOT_REQUIRED`, `CREDIT_LIMIT_EXCEEDED`, `CUSTOMER_REQUIRED_FOR_CREDIT`, `CUSTOMER_INACTIVE`, `CASH_SESSION_REQUIRED`, `INVALID_STATE_TRANSITION`, `IDEMPOTENCY_CONFLICT`, `CONCURRENT_MODIFICATION`.

### 5.7 Recall a lot
- **POST `/api/lots/{id}/recall`** — **Permission:** `lot.recall`.
- **Request:** `{ "reason": "Manufacturer notice 2026-10-17" }` (non-blank).
- **Response 200:** `{ lot: {id, status: "recalled", blockedAt, blockedBy}, buyers: [ { customerId, customerName, phone, documentNumber, documentType, issuedAt, quantityBase, unit } ], anonymousSales: [ {documentNumber, issuedAt, quantityBase} ], remainingStockBase }`
- **Errors:** `NOT_FOUND`, `ACCESS_DENIED`, `VALIDATION_FAILED`. Repeating on a recalled lot returns the same report.

### 5.8 Customer return
- **POST `/api/returns/customer`** — **Permission:** `return.customer.create`. **Header:** `Idempotency-Key`.
- **Request:** `{ "invoiceId": 4512, "reason": "Wrong product", "lines": [ { "saleLineId": 9001, "quantity": "2", "unitId": 7, "sealed": true, "disposition": "restock" } ] }`
- **Response 201:** `{ id, number, creditNote: {id, number, totalTtc}, lines: [{saleLineId, quantityBase, disposition, movementId?}], refund?: {…} }` (refund **[OPEN OD-005]**).
- **Errors:** `RETURN_QUANTITY_EXCEEDED`, `RETURN_NOT_RESTOCKABLE`, `INVALID_STATE_TRANSITION` (original not confirmed), `NOT_FOUND`.

### 5.9 Customer payment
- **POST `/api/payments/customers`** — **Permission:** `payment.customer.create`. **Header:** `Idempotency-Key`.
- **Request:** `{ "customerId": 55, "method": "cheque", "amount": "300.00", "reference": "CHQ-0412", "allocations": [ {"invoiceId": 4400, "amount": "200.00"}, {"invoiceId": 4421, "amount": "100.00"} ] }` (unallocated remainder policy **[OPEN OD-003]**).
- **Response 201:** `{ id, customerId, method, amount, allocations[…], unallocated, customerBalance }`
- **Validation:** amount > 0; Σ allocations ≤ amount; each ≤ outstanding; reference required for cheque/transfer (BR-PAY-001/002); cash needs open session.
- **Errors:** `PAYMENT_ALLOCATION_EXCEEDED`, `CASH_SESSION_REQUIRED`, `CUSTOMER_INACTIVE`, `VALIDATION_FAILED`.

### 5.10 Manual stock adjustment
- **POST `/api/inventory/adjustments`** — **Permission:** `stock.adjust`.
- **Request:** `{ "lotId": 88, "type": "adjustment_breakage", "quantity": "2", "unitId": null, "reason": "Dropped box" }` — `type` ∈ `adjustment_breakage | adjustment_expiry | adjustment_theft | adjustment_internal_use`; `quantity` positive, direction implied (outgoing).
- **Response 201:** `MovementEntry {id, lotId, type, quantity: "-2.000", reason, userId, createdAt, balanceAfter}`
- **Errors:** `ADJUSTMENT_REASON_REQUIRED`, `STOCK_INSUFFICIENT`, `NOT_FOUND`. Audited `STOCK_CORRECTION`.

### 5.11 Regularise an inventory count
- **POST `/api/inventory/counts/{id}/regularize`** — **Permission:** `inventory.validate`.
- **Request:** `{ "lineIds": [ … ] }` (omitted = all counted lines with variance).
- **Response 200:** `CountDetail` with each line `{lotId, theoreticalQty, countedQty, varianceQty, regularized, movementId}` and `status: "closed"`.
- **Errors:** `INVENTORY_NOT_OPEN`, `INVENTORY_LINE_ALREADY_REGULARIZED`, `STOCK_INSUFFICIENT`, `ADJUSTMENT_REASON_REQUIRED`.

### 5.12 Close a cash session
- **POST `/api/cash-sessions/{id}/close`** — **Permission:** `cash.open` (own) / `cash.manage`.
- **Request:** `{ "countedAmount": "1250.00" }`
- **Response 200:** `{ id, status: "closed", openingAmount, expectedAmount, countedAmount, varianceAmount, closedAt }`
- **Errors:** `CASH_SESSION_CLOSED`, `VALIDATION_FAILED`, `ACCESS_DENIED` (someone else's session without `cash.manage`).

## 6. API ↔ domain consistency notes

- Every aggregate in `domain-model.md` §8 maps to at least one resource above.
- Resources that do **not** exist by design: lots creation, stock movements update/delete, audit entries update/delete, payments update/delete (reversal only), sales line quantities after confirmation.
- Brief's endpoint list extended (documented, not silent): `/api/supplier-invoices`, `/api/cash-sessions`, `/api/customer-categories`, `/api/dosage-forms`, `/api/active-ingredients`, `/api/animal-species`, `/api/roles`, `/api/permissions`, `/api/audit-logs`, `/api/settings`, `/api/backups`. `/api/returns` is split into `/customer` and `/supplier`; `/api/payments` into `/customers` and `/suppliers`; stock/counts live under `/api/inventory` (ADR-022).

## 7. Open API items

OD-002/003 (confirm payload, balance), OD-004 (cancel), OD-005 (refund), OD-010 (lot override), OD-011 (credit-limit semantics), OD-012 (count scope), OD-013 (generate proposals), OD-015 (close PO), OD-018/019 (species filter, unit barcode), OD-020 (pdf formats), OD-021 (backup run).
