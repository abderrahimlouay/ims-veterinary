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
| Decimals | **JSON strings** (`"12.500"`) for money, quantities, percentages (ADR-021); scale: money 2, quantity 3 (whole numbers for non-divisible products, ADR-050), price per base unit and cost 4 (ADR-049), percent 2 |
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

Field codes (`errors[].code` of `VALIDATION_FAILED`, not error codes: they are not in `ErrorCode`, so they are listed here and each is tested where noted). Phase 5:

| Field code | Field | Meaning | Test |
|---|---|---|---|
| `EXPIRED` | `lines[i].expiryDate` (receipt) | a conforming line whose expiry is already past (BR-LOT-012) | `GoodsReceiptsTest.anExpiredLineIsReceivedOnlyAsNonConforming` |
| `NOT_ON_ORDER` | `lines[i].productId` / `lines[i].poLineId` (receipt) | product not on that order line, or an order line on a free receipt (ADR-096) | `GoodsReceiptsTest.onlySentOrPartiallyReceivedOrdersOfThatSupplierAndTheirProductsAreReceived` |
| `NOT_ORDER_SUPPLIER` | `supplierId` (receipt) | the receipt names another supplier than its order's (accepted 2026-10-24) | same test |
| `OTHER_SUPPLIER` | `lines[i].lotId` (supplier return) | lot received from another supplier (ADR-101) | `SupplierReturnsTest.moreThanTheBalanceOrALotOfAnotherSupplierIsRefused` |
| `DUPLICATE` | `lines[i].productId` (order), `lines[i].lotNumber` (receipt: "add the quantities on one line"), `lines[i].lotId` (return), `groups[i].supplierId` / `proposalIds` (conversion) | the same product, lot, supplier group or proposal twice | `PurchaseOrdersTest`, `GoodsReceiptsTest.aLotIsReceivedOnOneLineButTheSameNumberOfAnotherProductIsAnotherLot`, `SupplierReturnsTest`, `ReorderConversionTest` |
| `SELF` | `veterinarianId` (customer) | a customer cannot be its own follow-up veterinarian (ADR-109) | `CustomersTest` |
| `NOT_VETERINARIAN` | `veterinarianId` (customer) | not an active customer of type veterinarian (BR-CUST-001) | `CustomersTest` |
| `FORMAT` | `phone` (customer) | more than 15 digits once normalised (ADR-110) | `CustomersTest` |
| `PATTERN` | `Idempotency-Key` | the key is not a UUID (ADR-104) | `IdempotencyKeysTest`, `GoodsReceiptsTest.theSameIdempotencyKeyReturnsTheFirstReceipt` |

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
| `IDEMPOTENCY_CONFLICT` | 409 | key reused with different request | BR-SALE-016, BR-PURCH-013 |
| `RECEIPT_NOT_CANCELLABLE` | 409 | a lot of the receipt has a movement after the receipt's own; `context`: `lotId`, `lotNumber`, `movementId`, `movementType`, `movementAt` (ADR-103) | BR-PURCH-012 |
| `LOT_BLOCK_CONFIRMATION_REQUIRED` | 409 | a non-conforming receipt line would block a lot that already has stock; `context.lots[]`: `lotId`, `lotNumber`, `productId`, `quantityInStock` (ADR-095) | BR-PURCH-006 |
| `INVENTORY_NOT_OPEN` | 409 | count is closed/cancelled | BR-INV-004 |
| `OPENING_STOCK_CLOSED` | 409 | the opening-stock period is closed (setting `opening_stock_open`); use an inventory count | BR-STOCK-014, ADR-082 |
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
| `LOT_EXPIRY_MISMATCH` | 422 | existing lot number entered with another expiry date | BR-LOT-002, ADR-070 |
| `FEFO_VIOLATION` | 422 | FEFO order not respected without permission | BR-LOT-005 |
| `STOCK_INSUFFICIENT` | 422 | not enough stock | BR-STOCK-001, BR-SALE-015, BR-PURCH-009 |
| `ADJUSTMENT_REASON_REQUIRED` | 422 | reason missing | BR-STOCK-006, BR-INV-002 |
| `RECEIPT_QUANTITY_EXCEEDS_ORDER` | 422 | more than the outstanding quantity received (ADR-096) | BR-PURCH-004 |
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

`UserSummary {id, username, fullName, email, role: {id, name}, active, mustChangePassword, lastLoginAt}`; `UserDetail` = summary + `createdAt`, `version`. `RoleDetail {id, name, description, permissions: [code…]}`. No password data in any response except the one-time temporary password (ADR-045).

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| GET | `/api/users` | list (filters `q` = contains in username/full name/e-mail, `roleId`, `active`; sorted by username) | `user.read` | page of `UserSummary` |
| POST | `/api/users` | create | `user.manage` | `{username, fullName, email?, roleId, initialPassword}` → 201 `UserDetail` + `Location`; the user must change the password at first login; `DUPLICATE_RESOURCE` (`context.field` = `username`/`email`), `VALIDATION_FAILED` (`initialPassword` `TOO_SHORT`/`TOO_COMMON`, `roleId` `UNKNOWN`); audited `USER_CREATE` |
| GET | `/api/users/{id}` | view | `user.read` | `UserDetail` |
| PUT | `/api/users/{id}` | update full name, e-mail, role (username immutable) | `user.manage` | `{fullName, email?, roleId, version}` → `UserDetail`; `CONCURRENT_MODIFICATION`, `DUPLICATE_RESOURCE`, `INVALID_STATE_TRANSITION` (lock-out, ADR-042); audited `USER_UPDATE` with the changed fields |
| POST | `/api/users/{id}/deactivate` · `/activate` | toggle active | `user.manage` | → 200 `UserDetail`; deactivation ends the user's sessions; `INVALID_STATE_TRANSITION` for self-deactivation or administrator lock-out (ADR-042); audited `USER_DEACTIVATE` / `USER_UPDATE` |
| POST | `/api/users/{id}/reset-password` | issue temporary password | `user.manage` | → `{temporaryPassword}` shown once (ADR-045); ends the user's sessions; audited `PASSWORD_RESET` (without the password) |
| GET | `/api/roles`, `/api/roles/{id}`, `/api/permissions` | view | `user.read` | `RoleDetail[]`, `RoleDetail`, `[{code, description}]` |
| PUT | `/api/roles/{id}/permissions` | replace permission set | `role.manage` | `{permissionCodes[]}` → `RoleDetail`; `VALIDATION_FAILED` (`permissionCodes` `UNKNOWN`); `INVALID_STATE_TRANSITION` on administrator lock-out (ADR-042); audited `ROLE_PERMISSION_CHANGE` |

### 4.3 `/api/products` and reference data

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| GET | `/api/products` ● | search/list: `q` (name/SKU/active ingredient, contains, ADR-048), `categoryId`, `manufacturerId`, `ingredientId`, `speciesId` (target or withdrawal species, ADR-047), `includeArchived`; from Phase 3 `barcode` (exact, case-sensitive code, ADR-058), from Phase 4 `inStockOnly` (products with at least one sellable lot, filtered in the database, ADR-090) | `product.read` | page of `ProductSummary` (`sellableQtyBase`, `stockStatus` from Phase 4) |
| GET | `/api/products/by-barcode?code=` | scan lookup (exact, case-sensitive, ADR-058; the code as a query parameter so `/` and `%` work, ADR-071) | `product.read` | `{product: ProductSummary, unit: UnitView or null (= base unit), quantity: "1"}` (ADR-057); also for an archived product (documents refuse it, BR-PROD-004); `NOT_FOUND`; `VALIDATION_FAILED` when `code` is missing |
| POST | `/api/products` | create | `product.create` | `ProductWriteRequest {sku, commercialName, categoryId?, dosageFormId?, manufacturerId?, baseUnit, divisible, vatRate? (default: `default_vat_rate` setting), minStock?, maxStock?, reorderPoint?, purchasePrice?, salePrice?}` (prices per base unit, ≤ 4 decimals, ADR-049; a price other than 0 needs `price.edit`, else `ACCESS_DENIED`) → 201 `ProductDetail` + `Location`; `PRODUCT_SKU_DUPLICATE` (`context.field = sku`), `VALIDATION_FAILED` (field codes `REQUIRED`, `UNKNOWN`, `RANGE`, `NOT_NEGATIVE`, `SCALE`, `WHOLE_NUMBER` for a non-divisible product, `BELOW_MIN`) |
| GET | `/api/products/{id}` | detail | `product.read` | `ProductDetail {id, sku, commercialName, category, dosageForm, manufacturer (each `{id, name, code, country}` or null), baseUnit, divisible, vatRate, purchasePrice, salePrice, minStock, maxStock, reorderPoint, archived, ingredients: [{ingredient, strength}], targetSpecies: [species], withdrawalPeriods: [{species, meatDays, milkDays, eggsDays}], createdAt, updatedAt, version}` (ingredients, species and periods are not part of `version`); units and barcodes Phase 3; readable when archived (BR-PROD-004) |
| PUT | `/api/products/{id}` | update, also when archived (price fields need `price.edit` and are audited) | `product.update` (+ `price.edit`) | `ProductWriteRequest` + `version`, `vatRate` required; an omitted price is unchanged; changing a price needs `price.edit` (`ACCESS_DENIED`, nothing saved) and is audited `PRICE_CHANGE` with old/new values of the changed prices (BR-PROD-006); `CONCURRENT_MODIFICATION` |
| POST | `/api/products/{id}/archive` · `/restore` | archive / restore (idempotent) | `product.archive` | → 200 `ProductDetail` |
| GET | `/api/products/{id}/substitutes` | other non-archived products sharing at least one active ingredient **with sellable stock** (from Phase 4, ADR-089), most shared first, then by name (max 50) | `product.read` | list of `ProductSummary` |
| GET | `/api/products/{id}/lots` | all lots of the product in FEFO order (expiry date, then id), empty and unsellable ones included | `lot.read` | list of `LotSummary`; `NOT_FOUND` for an unknown product |
| GET/POST | `/api/products/{id}/units` | list / add packaging unit | `product.read` / `product.update` (+ `price.edit` for `salePrice`) | `{name (≤ 40), factorToBase, salePrice?, sellable}` → 201 `UnitView {id, name, factorToBase, salePrice (stored, 2 decimals, or null), effectiveSalePrice (stored, else factor × base price, 4 decimals half-up), sellable, version}`; list sorted by factor, then name; `VALIDATION_FAILED` field `name` (`REQUIRED`, `SIZE`), `factorToBase` (`REQUIRED`, `POSITIVE`, `SCALE` beyond 4 decimals, `WHOLE_NUMBER` for a non-divisible product, ADR-065), `salePrice` (`NOT_NEGATIVE`, `SCALE` beyond 2 decimals); `DUPLICATE_RESOURCE` (`context.field = name`, ignoring case and accents); `NOT_FOUND` for an unknown product |
| PUT | `/api/products/{id}/units/{unitId}` | edit unit | `product.update` (+ `price.edit` to set, change or clear `salePrice`) | same body + `version`; the body replaces the unit: `salePrice` omitted or `null` **clears** a stored price (derived again), so a client without `price.edit` sends the stored price back; `UNIT_FACTOR_LOCKED` (`context.field = factorToBase`) when the factor of a used unit changes (BR-UNIT-004); a price change is audited `PRICE_CHANGE` on entity `product_units` with old/new `salePrice` (`null` = derived) (ADR-063, BR-PROD-006); `NOT_FOUND` for a unit of another product; `CONCURRENT_MODIFICATION` |
| GET/POST/DELETE | `/api/products/{id}/barcodes[/{barcodeId}]` | list / add / remove barcode | `product.read` / `product.update` | `{code, codeType (EAN13, CODE128, QR), unitId?}` → 201 `{id, code, codeType, unit: UnitView or null}`; list in creation order; the code is stored exactly as sent (no trimming, ADR-058); DELETE → 204; `BARCODE_DUPLICATE` (`context.field = code`); `VALIDATION_FAILED` field `code` (`REQUIRED`, `SIZE`, `FORMAT`, `CHECK_DIGIT` for EAN13, ADR-067), `codeType` (`REQUIRED`, `UNKNOWN`) or `unitId` (`UNKNOWN`: not a unit of this product); `NOT_FOUND` for an unknown product or a barcode of another product |
| PUT | `/api/products/{id}/ingredients` | replace ingredient set | `product.update` | `[{ingredientId, strength?}]` (strength ≤ 60) → `[{ingredient, strength}]` sorted by name; `VALIDATION_FAILED` field `ingredients` (`UNKNOWN`, `DUPLICATE`, `SIZE`); not part of the product `version` |
| PUT | `/api/products/{id}/withdrawal-periods` | replace set (BR-PROD-007) | `product.update` | `[{speciesId, meatDays?, milkDays?, eggsDays?}]` → `[{species, meatDays, milkDays, eggsDays}]` sorted by species; missing days = not specified (`null`, never 0); `VALIDATION_FAILED` field `withdrawalPeriods` (`UNKNOWN`, `DUPLICATE`, `NOT_NEGATIVE`, `RANGE`) |
| PUT | `/api/products/{id}/target-species` | replace target species (ADR-047) | `product.update` | `{speciesIds[]}` → species sorted by name; `VALIDATION_FAILED` field `speciesIds` (`UNKNOWN`, `DUPLICATE`) |
| GET/POST/PUT/DELETE | `/api/categories` | therapeutic categories | `product.read` (GET) / `reference.manage` | GET → `[{id, name, code, country}]` sorted by name (`code` only for seeded entries, `country` only for manufacturers); POST `{name}` → 201; PUT `/{id}` `{name}`; DELETE `/{id}` → 204 only when unused, else `RESOURCE_IN_USE` (ADR-053); seeded entries: rename/delete → `INVALID_STATE_TRANSITION` (ADR-054); `DUPLICATE_RESOURCE` (case/accent-insensitive names) |
| GET/POST/PUT/DELETE | `/api/manufacturers` | manufacturers | same | `{name, country?}` |
| GET/POST/PUT/DELETE | `/api/dosage-forms`, `/api/active-ingredients`, `/api/animal-species` | reference lists | same | `{name}`; same rules |

### 4.4 `/api/lots`

> There is **no** `POST /api/lots`: lots are created only by goods receipts, opening stock, or inventory-found entries (BR-LOT-001); Phase 3 has no way to create one (ADR-064).
>
> `LotSummary {id, product: {id, sku, commercialName, baseUnit}, lotNumber, expiryDate, quantityOnHand, status, alertLevel, daysLeft}`; `LotDetail` adds `unitCost`, `blockedReason`, `blockedBy`, `blockedByName` (from Phase 4, ADR-089), `blockedAt`, `receivedAt`, `sellable`. `status` is the **effective** status (`active`, `blocked`, `recalled`, `expired`, `depleted`; ADR-059); `alertLevel` ∈ `expired`, `critical`, `warning`, `notice` or `null` (ADR-056).

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| GET | `/api/lots` | list: `productId`, `status` (effective status code), `alertLevel` (exactly that level of the current policy, so `alertLevel=X&hasStock=true` returns the lots counted under X by `/expiry-alerts`), `expiringWithinDays` (expiry ≤ business date + N: lots **already expired are included**; combine with `alertLevel` or `status` to exclude them), `hasStock` (`true`: quantity > 0), `q` (contained in the lot number, ignoring case and accents); ordered by expiry date then id | `lot.read` | page of `LotSummary`; `VALIDATION_FAILED` field `status` or `alertLevel` (`UNKNOWN`), or `expiringWithinDays` (`RANGE`) |
| GET | `/api/lots/{id}` | detail | `lot.read` | `LotDetail` (`blockedBy` is the user id); `NOT_FOUND` |
| GET | `/api/lots/fefo` ● | FEFO allocation preview (does **not** reserve) | `lot.read` | `?productId&quantity&unitId?` → allocation list |
| GET | `/api/lots/expiry-alerts` | lots with stock grouped by alert level (thresholds from settings, inclusive, ADR-056); every stored status is listed (a blocked lot still needs handling), each group in FEFO order | `lot.read` | `{expired[], critical[], warning[], notice[]` (each `LotSummary`)`, thresholds: {notice, warning, critical}, businessDate}` |
| GET | `/api/lots/{id}/movements` | movement history of the lot (Phase 4) | `stock.read` | page of `MovementEntry` |
| POST | `/api/lots/{id}/block` · `/unblock` | block / unblock (ADR-060) | `lot.block` / `lot.unblock` | `{reason}` (non-blank, ≤ 500) → `LotDetail`; block stores reason, user and time, unblock clears them; audited `LOT_BLOCK` / `LOT_UNBLOCK` with old/new `status` and the `reason`, in the same transaction; `INVALID_STATE_TRANSITION` (block when not active, unblock when not blocked, anything on a recalled lot); `NOT_FOUND`; allowed on an expired or empty lot |
| POST | `/api/lots/{id}/recall` ● | recall + buyers list (lists empty until Phase 7, ADR-068) | `lot.recall` | `{reason}` → `RecallReport`; audited `LOT_RECALL` |
| GET | `/api/lots/{id}/recall-report` | re-read buyers list | `lot.recall` | `RecallReport`; `INVALID_STATE_TRANSITION` when the lot is not recalled; `?format=pdf` arrives with printing (Phase 10) |

### 4.5 `/api/inventory` (stock, ledger, adjustments, opening stock, counts, reorder, integrity)

> Every write goes through `StockService` (ADR-088): lots locked `FOR UPDATE` in ascending id order, one transaction, READ COMMITTED, lock wait 5 s → `CONCURRENT_MODIFICATION` (ADR-012). Quantities are base-unit decimal strings with 3 decimals; signed where stated.

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| GET | `/api/inventory/stock` | product stock levels: `status` (`in_stock`, `low`, `out`), `belowReorder`, `categoryId`, `q` (as the product search), `includeArchived`; ordered like the product search; filtered in the database (ADR-090) | `stock.read` | page of `StockLevel` (ADR-080); `VALIDATION_FAILED` field `status` (`UNKNOWN`) |
| GET | `/api/inventory/movements` | ledger: `productId`, `lotId`, `type`, `userId`, `from`, `to` (dates in the business time zone), newest first | `stock.read` | page of `MovementEntry` |
| POST | `/api/inventory/adjustments` ● | manual adjustment (ADR-085) | `stock.adjust` | `{lotId, type (adjustment_breakage, adjustment_expiry, adjustment_theft, adjustment_internal_use), quantity (> 0, base unit), reason (≤ 500)}` → 201 `MovementEntry` (negative); audited `STOCK_CORRECTION`; `ADJUSTMENT_REASON_REQUIRED`, `STOCK_INSUFFICIENT`, `NOT_FOUND`, `VALIDATION_FAILED` (`type` `UNKNOWN`, `quantity` `POSITIVE`/`SCALE`/`WHOLE_NUMBER`) |
| POST | `/api/inventory/opening-stock` | initial stock entry (ADR-082…084) | `stock.adjust` | `{productId, lotNumber, expiryDate, quantity, unitId?, unitCost}` → 201 `{lot: LotDetail, movement: MovementEntry}` (`Location` of the lot); an existing lot is matched ignoring case and accents (ADR-058); audited `OPENING_STOCK`; `OPENING_STOCK_CLOSED`, `LOT_EXPIRY_MISMATCH`, `LOT_RECALLED`, `PRODUCT_ARCHIVED`, `VALIDATION_FAILED` (`unitCost` `REQUIRED`, `NOT_NEGATIVE`, `SCALE`; another cost on an existing lot is not refused: the lot cost becomes the weighted average, ADR-098; `quantity` `POSITIVE`/`SCALE`/`WHOLE_NUMBER`; `unitId` `UNKNOWN`) |
| GET | `/api/inventory/counts` | list counts: `status` (`open`, `closed`, `cancelled`), newest first | `inventory.count` | page of `CountSummary`; `VALIDATION_FAILED` field `status` (`UNKNOWN`) |
| POST | `/api/inventory/counts` | open a count: one line per lot with stock in scope, `theoreticalQty` = its balance | `inventory.count` | `{type (full, cycle), categoryId?, productIds?, notes? (≤ 1000)}` (cycle: exactly one of `categoryId` / `productIds`, ADR-073; archived products included) → 201 `CountDetail`; `INVENTORY_ALREADY_OPEN` (full), `VALIDATION_FAILED` (`type` `REQUIRED`/`UNKNOWN`; `scope` `REQUIRED`, `ONE_OF`, `NOT_ALLOWED` for a full count; `categoryId`/`productIds` `UNKNOWN`) |
| GET | `/api/inventory/counts/{id}` | detail with lines and variances | `inventory.count` | `CountDetail` |
| PUT | `/api/inventory/counts/{id}/lines/{lineId}` | enter or correct the counted quantity; locks the lot and takes a new watermark (ADR-072) | `inventory.count` | `{countedQty}` (≥ 0, > 0 for a found lot, base unit) → `CountLine`; `NOT_FOUND`, `INVENTORY_NOT_OPEN`, `INVENTORY_LINE_ALREADY_REGULARIZED`, `VALIDATION_FAILED` (`countedQty` `REQUIRED`, `NOT_NEGATIVE`, `POSITIVE`, `SCALE`, `WHOLE_NUMBER`) |
| POST | `/api/inventory/counts/{id}/lines` | add a line, counted at once: an existing lot of the scope `{lotId, countedQty ≥ 0}` or a lot unknown to the system `{productId, lotNumber, expiryDate, unitCost, countedQty > 0}` (ADR-074); a lot number that exists for the product (ignoring case and accents) with the same expiry is that lot | `inventory.count` | → 201 `CountLine`; `INVENTORY_NOT_OPEN`, `LOT_EXPIRY_MISMATCH`, `VALIDATION_FAILED` (`lotId` `UNKNOWN`/`DUPLICATE`; `productId` `UNKNOWN`/`OUT_OF_SCOPE`/`REQUIRED`; `lotNumber` `DUPLICATE`/`REQUIRED`/`SIZE`; `expiryDate`, `unitCost` `REQUIRED`; `unitCost` `NOT_NEGATIVE`/`SCALE`; `countedQty` as above). The found lot is created at regularisation |
| POST | `/api/inventory/counts/{id}/regularize` ● | post the accepted lines and close (ADR-075) | `inventory.validate` | `{lineIds?}` (omitted or empty: every counted line not yet regularised) → `CountDetail`; the count, then the lots in ascending id order are locked, lots found during the count created after them (ADR-074); one `adjustment_inventory` per line with variance ≠ 0 on the current balance (ADR-072), reason `Inventory #<id>`, reference `inventory`/count id, audited `STOCK_CORRECTION` with the lot's old and new quantity; zero variance: no movement, line regularised; all-or-nothing, the count stays open on any error: `INVENTORY_NOT_OPEN`, `INVENTORY_LINE_ALREADY_REGULARIZED` (a named line, checked before the count's status; `context.lineId`), `STOCK_INSUFFICIENT` (a lot would go negative; `context.lineId`, `lotId`, `available`, `variance`), `LOT_EXPIRY_MISMATCH` (a found lot number now exists with another expiry), `VALIDATION_FAILED` (`lineIds` `UNKNOWN`, `NOT_COUNTED`, `DUPLICATE` when a found lot now has another line in the count) |
| POST | `/api/inventory/counts/{id}/cancel` | cancel an open count (no movement) | `inventory.validate` | → `CountDetail`; `INVENTORY_NOT_OPEN` |
| GET | `/api/inventory/reorder-proposals` | list: `status` (`pending`, `converted`, `dismissed`), newest first | `reorder.read` | page of `ProposalEntry`; `VALIDATION_FAILED` field `status` (`UNKNOWN`) |
| POST | `/api/inventory/reorder-proposals/generate` | run the generation now (ADR-078), same code as the daily job; recorded in `reorder_runs` with who asked | `reorder.manage` | → `{created, updated, thresholdsIncomplete: [{id, sku, commercialName}], thresholdsInconsistent: [{id, sku, commercialName}]}` (`thresholdsIncomplete`: products needing a proposal without `maxStock`, ADR-077; `thresholdsInconsistent`: products whose `maxStock` is at or below `reorderPoint`, ADR-077 amendment — neither gets a proposal); `CONCURRENT_MODIFICATION` when another generation runs at the same moment |
| POST | `/api/inventory/reorder-proposals/{id}/dismiss` | dismiss a pending proposal (ADR-079); no new proposal for the product until a generation sees its sellable quantity above the reorder point | `reorder.manage` | → `ProposalEntry`; `NOT_FOUND`, `INVALID_STATE_TRANSITION` |
| POST | `/api/inventory/reorder-proposals/convert` | convert selected pending proposals to draft purchase orders (ADR-102, Phase 5) | `reorder.manage` + `purchase.create` | `{groups: [{supplierId, proposalIds[]}]}` → 201 `[PurchaseOrderDetail]` (one new draft per group, in order; lines in base units with the suggested quantity, price and discount prefilled as `/api/purchase-orders/prefill`); `INVALID_STATE_TRANSITION` (a proposal not pending — also the second of two parallel conversions), `NOT_FOUND` (unknown proposal), `SUPPLIER_INACTIVE`, `PRODUCT_ARCHIVED`, `VALIDATION_FAILED` (`groups` `REQUIRED`; `groups[i].supplierId` `REQUIRED`/`UNKNOWN`/`DUPLICATE` — one group per supplier; `groups[i].proposalIds` `REQUIRED`/`DUPLICATE`). The endpoint belongs to `purchasing` |
| GET | `/api/inventory/integrity-check` | latest stored run (ADR-086); the banner shows while its `mismatchCount` > 0 | `audit.read` | `IntegrityRun`; 204 when no run exists yet |
| POST | `/api/inventory/integrity-check` | run the check now | `audit.read` | → `IntegrityRun` |

**Contracts**

- `StockLevel {product: {id, sku, commercialName, baseUnit, archived}, totalQty, sellableQty, minStock, maxStock, reorderPoint, status (in_stock, low, out), belowReorder, nearestSellableExpiry (date or null)}` — computed on read with the current expiry policy (BR-STOCK-011/013).
- `MovementEntry {id, lot: {id, lotNumber, expiryDate}, product: {id, sku, commercialName, baseUnit}, type, quantity (signed), referenceType, referenceId, reason, user: {id, fullName}, createdAt}` — immutable (BR-STOCK-003/004).
- `CountSummary {id, type, status, startedAt, startedBy: {id, fullName}, closedAt, lineCount, countedCount}`.
- `CountDetail {id, type, status (open, closed, cancelled), scope: {categoryId, productIds}, notes, startedAt, startedBy, closedAt, closedBy, version, lines: [CountLine]}`.
- `CountLine {id, product: {id, sku, commercialName, baseUnit}, lot: {id, lotNumber, expiryDate} or null, foundLot: {lotNumber, expiryDate, unitCost} or null, theoreticalQty (balance at opening), countedQty, countedAt, countedBy, balanceAtCount, varianceQty (counted − balanceAtCount), movementsSinceCount (signed sum of the lot's movements after the watermark), currentBalance, regularized, adjustmentMovementId}` (ADR-072, ADR-074).
- `ProposalEntry {id, product: {id, sku, commercialName, baseUnit, divisible}, currentQty (sellable at the last generation), suggestedQty, reorderPoint, maxStock, status (pending, converted, dismissed), createdAt, updatedAt, dismissedAt, dismissedBy: {id, fullName} or null}`; `reorderPoint` and `maxStock` are the product's current thresholds — `supplierId` from Phase 5: the supplier a converted proposal was ordered from, else `null` (inventory does not read suppliers; the name comes from `/api/suppliers`, ADR-079, ADR-102).
- `IntegrityRun {id, startedAt, finishedAt, lotsChecked, mismatchCount, mismatches: [{lotId, lotNumber, productId, balance, ledgerSum}], triggeredBy: {id, fullName} or null (nightly job)}`.

### 4.6 `/api/suppliers`, `/api/supplier-invoices`

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| GET/POST | `/api/suppliers` | list (`q` contained in the name ignoring case and accents, `active`; by name) → page of `SupplierDetail` / create → 201 | `supplier.read` / `supplier.manage` | `SupplierDetail` fields: name (unique ignoring case and accents, `DUPLICATE_RESOURCE`), kind (`laboratory`/`wholesaler`), taxId (optional), phone, email, address, paymentTermsDays (≥ 0), active, version (ADR-100) |
| GET/PUT | `/api/suppliers/{id}` | view / update | `supplier.read` / `supplier.manage` | `SupplierDetail {id, name, kind, taxId, phone, email, address, paymentTermsDays, active, createdAt, updatedAt, version}`; PUT with `version` (`CONCURRENT_MODIFICATION`); `VALIDATION_FAILED` (`name` `REQUIRED`/`SIZE`, `kind` `UNKNOWN`, `paymentTermsDays` `RANGE`, `email` `EMAIL`) |
| POST | `/api/suppliers/{id}/deactivate` · `/activate` | toggle, allowed with open orders (their receipts are then refused, ADR-100) | `supplier.manage` | → `SupplierDetail` |
| GET/POST | `/api/suppliers/{id}/prices` | price & discount history (`productId`) / add a manual entry | `supplier.read` / `supplier.manage` | `{productId, unitPrice (per base unit, 4 decimals), discountPct (0–100), validFrom}`; receipts append entries when the price or discount changes (BR-PURCH-008); each entry `PriceEntry {id, product: {id, sku, commercialName, baseUnit}, unitPrice, discountPct, validFrom, createdAt, createdBy: {id, fullName}}`, newest first, plus `receiptId` (the receipt that appended it, else `null`) and `cancelled` (ADR-106); `validFrom` defaults to the business date; `PRODUCT_ARCHIVED`, `VALIDATION_FAILED` (`productId` `REQUIRED`/`UNKNOWN`, `unitPrice` `REQUIRED`/`NOT_NEGATIVE`/`SCALE`, `discountPct` `RANGE`/`SCALE`) |
| GET | `/api/suppliers/{id}/payables` | open invoices and remaining — **Phase 9** | `payment.read` | list with `remaining` |
| GET/POST | `/api/supplier-invoices` | list / record — **Phase 9** | `supplierinvoice.manage` | `{supplierId, invoiceNumber, receiptId?, invoiceDate, dueDate?, totalTtc, creditNote}`; `DUPLICATE_RESOURCE` |
| GET | `/api/supplier-invoices/{id}` | detail with payments — **Phase 9** | `supplierinvoice.manage` | |

### 4.7 `/api/purchase-orders`

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| GET | `/api/purchase-orders` | list: `status`, `supplierId`, `from`, `to`, newest first | `purchase.read` | page of `PurchaseOrderSummary {id, number, supplier, status, orderDate, expectedDate, totalTtc}` |
| POST | `/api/purchase-orders` | create draft, numbered `PO-YYYY-NNNNNN` at creation (ADR-097) | `purchase.create` | `{supplierId, expectedDate?, lines[{productId, unitId?, quantity, unitPrice (per ordered unit, 4 decimals), discountPct, vatRate?}]}` (`vatRate` defaults to the product's, ADR-105) → 201 `PurchaseOrderDetail` (server totals per line, ADR-093; amounts sent by the client are ignored); a refused creation takes no number; `SUPPLIER_INACTIVE`, `PRODUCT_ARCHIVED`, `VALIDATION_FAILED` (`supplierId` `REQUIRED`/`UNKNOWN`, `lines` `REQUIRED`, `lines[i].productId` `UNKNOWN`/`DUPLICATE`, `lines[i].unitId` `UNKNOWN`, `lines[i].quantity` `POSITIVE`/`SCALE`/`WHOLE_NUMBER`, `lines[i].unitPrice` `SCALE`/`NOT_NEGATIVE`, `lines[i].discountPct`/`vatRate` `RANGE`/`SCALE`) |
| GET | `/api/purchase-orders/{id}` | detail | `purchase.read` | `PurchaseOrderDetail {id, number, supplier, status, orderDate, expectedDate, lines[{id, product, unit, quantity, quantityBase, receivedBase, outstandingBase, unitPrice, discountPct, vatRate, lineHt, lineVat}], totalHt, totalVat, totalTtc, closedReason, closedBy, closedAt, cancelReason, cancelledBy, cancelledAt, receipts[{id, number, receivedAt, status}] (from slice 5), createdBy, createdAt, validatedAt, sentAt, version}` |
| GET | `/api/purchase-orders/prefill` | price prefill for a line: latest supplier price, else `purchase_price` (ADR-099) | `purchase.create` | `?supplierId&productId&unitId?` → `{unitPrice, discountPct, vatRate, source (history, product)}` |
| PUT | `/api/purchase-orders/{id}` | edit draft (full replace of lines) | `purchase.create` | with `version`; `DOCUMENT_IMMUTABLE` if not draft, `CONCURRENT_MODIFICATION` |
| POST | `/api/purchase-orders/{id}/validate` · `/send` · `/cancel` | status actions; cancel needs `{reason}` and is audited `PO_CANCEL` with the reason; a cancelled order keeps its number | `purchase.validate` | → `PurchaseOrderDetail`; `INVALID_STATE_TRANSITION` (every transition outside BR-PURCH-001), `VALIDATION_FAILED` (`reason` `REQUIRED`); validate also `SUPPLIER_INACTIVE`, `PRODUCT_ARCHIVED` |
| POST | `/api/purchase-orders/{id}/close` | forced close of a partially received order (ADR-096), audited `PO_CLOSE` | `purchase.validate` | `{reason}` → `PurchaseOrderDetail`; `INVALID_STATE_TRANSITION` |
| GET | `/api/purchase-orders/{id}/pdf` | printable PO — **Phase 10** | `purchase.read` | `application/pdf` |

### 4.8 `/api/goods-receipts`

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| GET | `/api/goods-receipts` | list: `supplierId`, `purchaseOrderId`, `status`, `from`, `to`, newest first | `receipt.read` | page of `ReceiptSummary {id, number, supplier, purchaseOrder, receivedAt, status}` |
| GET | `/api/goods-receipts/{id}` | detail with lots and movements | `receipt.read` | `ReceiptDetail` (§5.4 response) with `status`, `cancelReason`, `cancelledBy`, `cancelledAt` |
| POST | `/api/goods-receipts` ● | confirm a receipt (creates/feeds lots, posts stock) | `receipt.create` | see §5.4 |
| POST | `/api/goods-receipts/{id}/cancel` ● | cancel the whole receipt (ADR-103) | `receipt.cancel` | `{reason}` → `ReceiptDetail` (`cancelled`); `RECEIPT_NOT_CANCELLABLE` (`context.lotId`, `lotNumber`, `movementId`, `movementType`, `movementAt`), `INVALID_STATE_TRANSITION` (already cancelled), `VALIDATION_FAILED` (`reason`); audited `RECEIPT_CANCEL` |

### 4.9 `/api/customers`, `/api/customer-categories`

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| GET | `/api/customers` | search: `q` (name, phone, tax id), `type`, `wilaya`, `commune`, `categoryId`, `active` | `customer.read` | page of `CustomerSummary` |
| POST | `/api/customers/similar` | before saving, a **POST** so that names and phones never travel in a URL (ADR-114): `{name?, phone?, taxId?, excludeId?}` → possible duplicates (ADR-110) | `customer.read` | `[{customerId, field}]` (`field`: `name`, `phone`, `taxId`) — ids and fields only |
| POST | `/api/customers` | create (credit limit 0, ADR-109; a `creditLimit` in the body is **ignored**, as on PUT: only the credit-limit endpoint changes it) | `customer.manage` | `{name, customerType, categoryId?, farmingType?, wilaya?, commune?, address?, phone?, email?, taxId?, paymentTermsDays?, veterinarianId?}`; `DUPLICATE_RESOURCE` (`context: {field, customerId}`), `VALIDATION_FAILED` |
| DELETE | `/api/customers/{id}` | not offered: customers are never deleted (ADR-109) | — | 405 |
| GET/PUT | `/api/customers/{id}` | view / update (credit limit excluded) | `customer.read` / `customer.manage` | with `version`; `CustomerDetail {id, name, customerType, category: {id, name, discountPct}, farmingType, wilaya, commune, address, phone, email, taxId (normalised), paymentTermsDays, veterinarian: {id, name}, creditLimit, active, createdAt, updatedAt, version}`; `CustomerSummary {id, name, customerType, category, wilaya, commune, phone, active}`; `RESOURCE_IN_USE` (a referenced veterinarian's type), `DUPLICATE_RESOURCE`, `VALIDATION_FAILED` (`name` `REQUIRED`, `customerType` / `categoryId` `UNKNOWN`, `paymentTermsDays` `RANGE`, `email` `EMAIL`, `phone` `FORMAT`, `veterinarianId` `SELF` / `NOT_VETERINARIAN`) |
| PUT | `/api/customers/{id}/credit-limit` | change credit limit under the customer-row lock, audited `CREDIT_LIMIT_CHANGE` (old and new value, also to and from `null`) | `customer.credit.manage` | `{creditLimit, version}` — `creditLimit` must be present: `null` = no limit, `"0.00"` = cash only, else ≥ 0 with 2 decimals (ADR-108) → `CustomerDetail`; `VALIDATION_FAILED` (`creditLimit` `REQUIRED` / `FORMAT` / `NOT_NEGATIVE` / `SCALE` / `RANGE`), `CONCURRENT_MODIFICATION` (stale version, or a parallel change), `NOT_FOUND` |
| POST | `/api/customers/{id}/deactivate` · `/activate` | toggle | `customer.manage` | |
| GET | `/api/customers/{id}/balance` | balance, limit, available credit | `customer.read` | definition **[OPEN OD-003]** |
| GET | `/api/customers/{id}/statement` | account statement `?from&to` (+ `format=pdf`) | `statement.read` | chronological lines + running balance |
| GET | `/api/customers/{id}/open-invoices` | invoices with outstanding amount (for allocation) | `payment.read` | |
| GET | `/api/customers/{id}/sales` | sales history | `sale.read` | |
| GET | `/api/customers/{id}/prices` | the customer's prices | `customer.read` | `[{product: {id, sku, commercialName, baseUnit}, unitPrice, updatedAt}]` (per base unit) |
| PUT / DELETE | `/api/customers/{id}/prices/{productId}` | set or change / remove one price (ADR-111), audited `PRICE_CHANGE` | `customer.price.manage` | `{unitPrice}` (≥ 0, 4 decimals); `PRODUCT_ARCHIVED`, `NOT_FOUND` |
| GET/POST/PUT | `/api/customer-categories` | categories with default discount; no delete (ADR-112) | `customer.read` / `customer.price.manage` | `{name, discountPct}` (+ `version` on PUT); `DUPLICATE_RESOURCE` (name ignoring case and accents); a discount change is audited `PRICE_CHANGE` |

### 4.10 `/api/sales`

| Method | Path | Purpose | Permission | Contract |
|---|---|---|---|---|
| GET | `/api/sales` | list: `docType`, `status`, `customerId`, `number`, `from`, `to` | `sale.read` | page |
| POST | `/api/sales` | create a **draft** document | `sale.create` | `{docType, customerId?, parentId?, creditSale?, dueDate?, notes?}` → 201 |
| GET | `/api/sales/{id}` | detail with lines, totals, warnings | `sale.read` | |
| POST | `/api/sales/{id}/lines` ● | add line; server resolves unit, price, FEFO lot(s), warnings | `sale.create` | see §5.5 → updated document |
| PUT/DELETE | `/api/sales/{id}/lines/{lineId}` | edit / remove draft line | `sale.create` | `DOCUMENT_IMMUTABLE` if not draft |
| PUT | `/api/sales/{id}` | edit draft header | `sale.create` | `version` |
| DELETE | `/api/sales/{id}` | abandon a draft | `sale.create` | only drafts (sales numbering decided in Phase 7, format ADR-097) |
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
| GET | `/api/returns/supplier` · `/{id}` | list (`supplierId`, `from`, `to` business dates inclusive; newest first) / detail | `return.read` | page of `SupplierReturnSummary {id, number, supplier, reason, createdAt}` / `SupplierReturnDetail {id, number, supplier, reason, createdAt, createdBy, lines[{id, product, lotId, lotNumber, expiryDate, quantityBase, movementId}]}` |
| POST | `/api/returns/supplier` | create supplier return (ADR-101), numbered `SR`, audited `RETURN_CREATE` | `return.supplier.create` | `{supplierId, reason, lines[{lotId, quantity, unitId?}]}` → 201 `SupplierReturnDetail`; `STOCK_INSUFFICIENT`, `SUPPLIER_INACTIVE` is not checked (returns to an inactive supplier are allowed), `VALIDATION_FAILED` (`supplierId` `REQUIRED`/`UNKNOWN`, `reason` `REQUIRED`, `lines` `REQUIRED`, `lotId` `REQUIRED`/`UNKNOWN`/`OTHER_SUPPLIER`/`DUPLICATE`, `quantity` `POSITIVE`/`SCALE`/`WHOLE_NUMBER`, `unitId` `UNKNOWN`); lots of any status; the lots are locked in ascending id by `StockService` |

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
| GET | `/api/audit-logs` | filters `entity`, `entityId`, `userId`, `action`, `from`, `to` (business dates, both inclusive, business time zone); newest first | `audit.read` | page of `AuditEntry {id, userId, username, userFullName, action, entity, entityId, oldValues, newValues, ipAddress, createdAt}` (read-only; values are JSON objects; user fields `null` for system actions, ADR-046) |
| GET | `/api/settings/public` | settings needed by the UI | authenticated | → `{expiryAlertDays: [60,30,15], defaultVatRate: "9.00", currency, locale, timeZone, openingStockOpen}` (`openingStockOpen` from Phase 4, ADR-082: the opening-stock page explains a closed period before any entry; no security settings) |
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
- **GET `/api/products?q=amox&categoryId=1&speciesId=2&page=0&size=20`** — fast search for POS/catalogue. **Permission:** `product.read`.
- **Criteria (all optional, ADR-047/048):** `q` anywhere in the commercial name, the SKU or an active-ingredient name, case- and accent-insensitive, `%` and `_` taken literally; `categoryId`, `manufacturerId`, `ingredientId`, `speciesId` (target or withdrawal-period species); `includeArchived` (default `false`). From Phase 3 `barcode`, from Phase 4 `inStockOnly`.
- **Order:** exact SKU match, then names starting with `q`, then the other matches, then by name.
- **Response:** page of `ProductSummary {id, sku, commercialName, category, baseUnit, salePrice, archived}`; from Phase 4 also `sellableQtyBase`, `totalQtyBase`, `stockStatus` (`in_stock|low|out`, out = sellable 0, low = ≤ `min_stock`) and `nearestExpiry` (of a sellable lot with stock, or null) — also in the substitutes and the scan result (ADR-089).
- **Validation:** `size ≤ 100`. **Performance:** < 1 s on the 50,000-product reference dataset (NFR-002, ADR-048; measured 61–125 ms, `testing.md` §8.1). **Errors:** `VALIDATION_FAILED`.

### 5.3 FEFO preview
- **GET `/api/lots/fefo?productId=12&quantity=3&unitId=7`** — **Permission:** `lot.read`.
- **Response:** `{ "requestedBase": "15.000", "allocations": [ {"lotId": 88, "lotNumber": "L2407A", "expiryDate": "2027-03-31", "quantityBase": "10.000", "available": "10.000", "alertLevel": "notice"}, {"lotId": 91, …, "quantityBase": "5.000"} ], "shortfallBase": "0.000" }`
- **Rules:** only sellable lots (BR-STOCK-011, ADR-055); order `expiry_date, id`; `requestedBase = quantity × factor`, exact (ADR-065); **informational** — stock is re-validated at confirmation. **Errors:** `NOT_FOUND`, `UNIT_NOT_SELLABLE`, `PRODUCT_ARCHIVED`, `VALIDATION_FAILED` (`productId`/`quantity` missing; `quantity` `POSITIVE` / `SCALE` (more than 3 decimals, or a base quantity needing more) / `WHOLE_NUMBER`, `unitId` `UNKNOWN` when the unit belongs to another product). `available` is the lot's whole stock on hand; `alertLevel` may be `null`.

### 5.4 Confirm goods receipt
- **POST `/api/goods-receipts`** — **Permission:** `receipt.create`. **Header:** `Idempotency-Key` (UUID, ADR-104).
- **Request:**
```json
{ "supplierId": 3, "purchaseOrderId": 41, "supplierDocRef": "BL-88213", "notes": "",
  "confirmLotBlocking": false,
  "lines": [ { "poLineId": 120, "productId": 12, "unitId": 7, "quantity": "10",
               "lotNumber": "L2407A", "expiryDate": "2027-03-31",
               "unitCost": "0.3500", "conforming": true } ] }
```
- **Response 201:** `{ id, number, status, supplier, purchaseOrder: {id, number, status}, supplierDocRef, notes, receivedAt, receivedBy, lines: [{id, poLineId, product, lotId, lotNumber, expiryDate, quantityBase, unitCost, conforming, lotBlocked, movementId}], cancelReason, cancelledBy, cancelledAt }`. A repeat with the same `Idempotency-Key` and body returns the first receipt (201, nothing posted again).
- **Validation:** the header is optional (without it nothing is stored); a key that is not a UUID → `Idempotency-Key` `PATTERN`. `supplierId` required without an order; with an order it may be omitted (the order's supplier) and another one is `supplierId` `NOT_ORDER_SUPPLIER`; `lines` `REQUIRED` (at least one); `conforming` defaults to `true`; one line per lot (same product and lot number, ignoring case and accents → `lotNumber` `DUPLICATE`); `supplierDocRef` ≤ 60; `lotNumber` non-blank, ≤ 60, and `expiryDate` present (BR-LOT-001/BR-PURCH-005); quantity > 0 in a unit of the product; `unitCost` ≥ 0 per base unit, 4 decimals; a conforming line's expiry not past (`EXPIRED`, BR-LOT-012); with a PO: order `sent` or `partially_received`, `poLineId` required, of that order, product of that line (`productId` `NOT_ON_ORDER`); without a PO no `poLineId` (`poLineId` `NOT_ON_ORDER`); several lines may receive one order line (several lots); quantity ≤ outstanding (`RECEIPT_QUANTITY_EXCEEDS_ORDER`); `quantityBase = quantity × unit factor` computed server-side.
- **Effects:** lot found or created (supplier set on creation), weighted-average cost (ADR-098), `purchase_receipt` movement through `StockService`, non-conforming lot blocked (ADR-095), PO progress, price history (ADR-100), number `GR-YYYY-NNNNNN`.
- **Errors:** `VALIDATION_FAILED`, `SUPPLIER_INACTIVE`, `PRODUCT_ARCHIVED`, `INVALID_STATE_TRANSITION` (PO not receivable), `RECEIPT_QUANTITY_EXCEEDS_ORDER`, `LOT_EXPIRY_MISMATCH`, `LOT_RECALLED`, `LOT_BLOCK_CONFIRMATION_REQUIRED`, `NOT_FOUND`, `IDEMPOTENCY_CONFLICT`.
- **Transaction:** all-or-nothing (BR-PURCH-007); lots locked in ascending id.

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
- **Request:** `{ "reason": "Manufacturer notice 2026-10-17" }` (non-blank, ≤ 500).
- **Response 200:** `{ lot: {id, status: "recalled", blockedAt, blockedBy}, buyers: [ { customerId, customerName, phone, documentNumber, documentType, issuedAt, quantityBase, unit } ], anonymousSales: [ {documentNumber, issuedAt, quantityBase} ], remainingStockBase }`
- **Errors:** `NOT_FOUND`, `ACCESS_DENIED`, `VALIDATION_FAILED`. Repeating on a recalled lot returns the same report: nothing changes (the first reason, user and time stay) and no second `LOT_RECALL` is written. Until sales exist (Phase 7) `buyers` and `anonymousSales` are empty (ADR-068).

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
- **Request:** `{ "lineIds": [ … ] }` (omitted or empty = every counted line not yet regularised).
- **Response 200:** `CountDetail` (`status: "closed"`) whose accepted lines have `regularized: true` and `adjustmentMovementId` (null for a zero variance); a found lot's line now has its `lot`.
- **Errors:** `INVENTORY_NOT_OPEN`, `INVENTORY_LINE_ALREADY_REGULARIZED`, `STOCK_INSUFFICIENT`, `LOT_EXPIRY_MISMATCH`, `VALIDATION_FAILED` (see §4.5).

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

OD-002/003 (confirm payload, balance), OD-004 (cancel), OD-005 (refund), OD-010 (lot override), OD-020 (pdf formats), OD-021 (backup run).
