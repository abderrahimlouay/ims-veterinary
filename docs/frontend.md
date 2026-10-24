# Frontend Design — IMS Vétérinaire

> **Status:** Documentation / Architecture Phase — Implementation Not Started
> **Scope:** structure and behaviour only. No React code. The design-system prototype (`Vet stock design system.html`) is a **visual reference**; it is not production code (it relies on a CDN build of Tailwind and in-browser transpilation).
> **Last updated:** 2026-10-18

Tags: `SRC` Spec · `REC` recommendation · `OD-nnn` open decision · `DR-nnn` review item.

## 1. Stack and principles (ADR-023)

| Concern | Choice |
|---|---|
| Build / language | React + Vite, **TypeScript** (strict) |
| Routing | React Router (data router, route-level code splitting) |
| Server state | TanStack Query (cache, invalidation, retries off for mutations) |
| Forms | React Hook Form + Zod schemas (client validation mirrors, never replaces, server validation) |
| Styling | **Built** Tailwind CSS driven by design tokens (CSS variables); no CDN |
| Tests | Vitest + React Testing Library + MSW; Playwright for end-to-end |
| API client | one typed fetch wrapper; errors normalised to Problem Details (`api.md` §2) |

Principles:
1. **The backend is the authority.** The UI hides what the user cannot do for comfort only (BR-SEC-002); every action still handles `ACCESS_DENIED`.
2. **Never compute truth on the client.** Totals, prices, FEFO lots, stock status, and balances shown are those returned by the API. The client may display a provisional line total while typing but must replace it with the server value.
3. **Decimals are strings** (ADR-021). Use a decimal library for any display arithmetic; never `Number` on money/quantity.
4. **Speed first for the counter** (NFR-002): keyboard-driven product search, barcode scan, one-screen POS.
5. **Every state is designed:** loading, empty, error, forbidden, stale-version conflict.

## 2. Application structure

```
frontend/src/
  app/            router, providers, layouts, guards
  api/            client, error normaliser, query keys, per-domain hooks
  features/       auth, products, lots, inventory, purchasing, sales, returns,
                  customers, suppliers, payments, cash, reports, admin
  components/     design-system primitives (Button, Input, Table, Modal…)
  lib/            decimal, date, format, permissions, i18n
  styles/         tokens.css, tailwind config
```

Rules: a feature never imports another feature's internals (only its public `index`); shared primitives live in `components/`; no business rules in components — put them in feature hooks/helpers and keep them display-only.

## 3. Routing and layouts

| Layout | Used by | Contents |
|---|---|---|
| `AuthLayout` | `/login` | centred card |
| `AppLayout` | everything else | sidebar (permission-filtered), top bar (user, search, alerts badge), breadcrumb, content |
| `PosLayout` | `/pos` | full-screen counter UI, minimal chrome, always-visible cash-session status |

Guards: `RequireAuth` (redirect to `/login`, preserve target), `RequirePermission(code)` (renders the *Forbidden* page, never a blank), `UnsavedChangesGuard` on dirty forms.

### 3.1 Route map

| Route | Page | Permission to show |
|---|---|---|
| `/login` | Login | public |
| `/` | Dashboard (expiry alerts, low stock, open cash session, pending reorder proposals) | authenticated |
| `/products`, `/products/new`, `/products/:id` | catalogue, create, detail/edit (tabs: General, Units & barcodes, Ingredients, Withdrawal periods, Lots, History) | `product.read` / `product.create` |
| `/lots`, `/lots/:id` | lot list, lot detail + movements, block/unblock/recall | `lot.read` |
| `/stock` | stock levels, alerts, movement ledger | `stock.read` |
| `/stock/adjustments/new` | manual adjustment | `stock.adjust` |
| `/stock/opening-stock/new` | opening stock (ADR-082…084) | `stock.adjust` |
| `/stock/integrity` | latest integrity run and run on demand (ADR-086) | `audit.read` |
| `/inventory-counts`, `/inventory-counts/new`, `/inventory-counts/:id` | counts, entry grid, regularise | `inventory.count` |
| `/reorder` | reorder proposals | `reorder.read` |
| `/suppliers`, `/suppliers/new`, `/suppliers/:id` | list, new (`supplier.manage`), detail and edit with price history (payables Phase 9) | `supplier.read` |
| `/purchase-orders`, `/new`, `/:id`, `/:id/edit` | PO list, new and draft editor (`purchase.create`), detail | `purchase.read` |
| `/goods-receipts`, `/new` (`?orderId=` or free), `/:id` | receipts, entry (`receipt.create`), detail and cancellation (`receipt.cancel`) | `receipt.read` |
| `/supplier-returns`, `/new`, `/:id` | supplier returns (`return.supplier.create` to create) | `return.read` |
| `/supplier-invoices`, `/:id` | supplier invoices & payments | `supplierinvoice.manage` |
| `/customers`, `/:id` | list, detail (balance, statement, prices, history) | `customer.read` |
| `/pos` | counter sale | `sale.create` |
| `/sales`, `/sales/:id` | documents list/detail (quote, DN, invoice) | `sale.read` |
| `/returns/customer`, `/returns/supplier` (+ `/new`) | returns | `return.read` |
| `/payments/customers`, `/payments/suppliers` | payments | `payment.read` |
| `/cash-sessions`, `/cash-sessions/:id` | sessions, report | `cash.open` / `cash.manage` |
| `/reports/*` | reports (revenue, margin, valuation, stats, balances, payables, traceability) | per report permission |
| `/catalog/references` | reference lists (species, categories, dosage forms, active ingredients, manufacturers); seeded values translated by code (ADR-054) | `product.read` (changes: `reference.manage`) |
| `/admin/users`, `/admin/roles`, `/admin/settings`, `/admin/audit`, `/admin/backups` | administration | `user.read`, `role.manage`, `settings.manage`, `audit.read`, `backup.manage` |
| `/account` | profile, change password | authenticated |
| `*` | Not-found | — |

## 4. Page design by domain

For each: purpose → main elements → key behaviours → states.

### 4.1 Product catalogue and detail
- **List:** search box (name/SKU/active ingredient, contains, ADR-048; barcode from Phase 3), filters (category, manufacturer, active ingredient, species — target or withdrawal species, ADR-047 —, show archived; in-stock only from Phase 4); table columns: SKU, name, category, base unit, sale price (sellable stock and nearest expiry from Phase 4). Seeded reference values are displayed through their code in the UI language (ADR-054).
- **Detail form:** tabs per `api.md` §4.3. Price fields read-only without `price.edit`. Archive instead of delete (BR-PROD-004). Substitute panel (FR-PROD) lists same-ingredient products with stock. The max-stock field warns, without refusing, when it is at or below a reorder point > 0: no reorder proposal could be generated (ADR-092).
- **Units and barcodes tabs (Phase 3):** units table (name, factor, stored or derived price, sellable) with add/edit (Units tab delivered in slice `phase-3/catalog-units`; empty, loading and error states; a locked factor shows `UNIT_FACTOR_LOCKED`); the price field is read-only without `price.edit` and the stored price is sent back unchanged (ADR-063); barcodes table (code, type, unit or "base unit") with add/remove and per-type format hints (ADR-067) (Barcodes tab delivered in slice `phase-3/catalog-barcodes`).
- **Behaviours:** barcode field accepts scanner input (Enter-terminated); in the list search box, Enter looks the text up as an exact barcode (`GET /api/products/by-barcode?code=`, ADR-071) and opens the product carrying it, otherwise the text search continues; duplicate SKU/barcode shows field-level error from `PRODUCT_SKU_DUPLICATE` / `BARCODE_DUPLICATE`; `CONCURRENT_MODIFICATION` opens a "reload latest" dialog.

### 4.2 Lots and expiry
- **Lot list:** filters (product, status, alert level, "expired or expiring within N days" — labelled so, with the hint that expired lots are included —, with stock); expiry shown with the level badge returned by the API (expired / critical / warning / notice, ADR-056; thresholds come from settings, never hard-coded, DR-015). Until opening stock exists (Phase 4) the list shows an empty state explaining that lots appear with opening stock and receipts (ADR-064).
- **Lot detail:** header, effective status (ADR-059), balance, who blocked or recalled the lot and when (ADR-089), actions Block / Unblock / Recall (modal with mandatory reason; Unblock never offered on a recalled lot, ADR-060); movement history from Phase 4. Recall result shows the buyers table with export/print; until Phase 7 it shows that no sales exist yet (ADR-068).
- **Expiry input** (opening stock Phase 4, receipts Phase 5): full date or `MM/YYYY`, sent as the last day of the month (ADR-055).
- **Dashboard widget:** counts per alert level linking to the filtered list; empty state when no lot is near expiry.
- **Delivered in Phase 3 (slice `phase-3/frontend-lots`):** `/lots` with the four filters in the URL and the empty state of ADR-064; `/lots/:id` with Block / Unblock / Recall dialogs (reason required, ≤ 500) shown only when the transition exists and the user holds its permission, and the recall report (remaining stock, "no sales yet" until Phase 7); the product Lots tab (FEFO order, shown with `lot.read`); status and level badges from the API using the tone tints checked by `contrast.test.ts`; the dashboard widget, whose tiles read Expired / Critical (≤ n days) / Warning (≤ n days) / Notice (≤ n days) with the thresholds from the settings, and each open `/lots?alertLevel=<level>&hasStock=true`: exactly the lots counted (user decision 2026-10-22). The lot list also filters by alert level. `blockedBy` (a user id) is not shown until a user name is available to the inventory screens. axe runs on the list and on the detail with a dialog open.

### 4.3 Stock and adjustments
- **Stock levels:** sellable vs total, thresholds, status (in stock / low / out, ADR-080), below-reorder filter.
- **Ledger:** read-only, filter by product/lot/type/user/date; signed quantities coloured by direction **and** with +/− sign (not colour alone).
- **Adjustment form:** lot picker (shows balance; blocked, recalled and expired lots included), type, quantity in base units, mandatory reason; inline preview "balance after" (ADR-085).
- **Opening stock form:** product, lot number, expiry (date or `MM/YYYY`), quantity with unit, unit cost prefilled from the purchase price; warning when the lot is already expired; while `opening_stock_open` is false the page explains that stock found after go-live goes through an inventory count (ADR-082…084).
- **Integrity banner:** users with `audit.read` see a banner while the latest integrity run has mismatches, with a link to its report; it disappears after a clean run (ADR-086).
- **Product list and dashboard (ADR-089):** sellable quantity, status and nearest expiry columns and an "in stock only" filter; stock-aware substitutes; a low-stock dashboard widget; the lot page shows who blocked or recalled the lot.
- **Reorder proposals:** list by status, *Generate now* (shows the products with thresholds incomplete), *Dismiss*; *Convert to purchase order* arrives in Phase 5.
- **Delivered in Phase 4 (slice `phase-4/frontend-stock`):** `/stock` with two tabs — stock levels (search, status, below-reorder, in the URL; status and below-reorder badges from the API) and the ledger (`?view=movements`, type and date filters, `productId`/`lotId` from links; quantities shown with a +/− sign and a colour); `/stock/adjustments/new` (lot search or `?lotId=`, type, quantity, reason, "balance after" preview computed exactly on decimal strings — never floats — and a warning when it would go negative; the server decides) opening the lot page, which now lists the lot's movements (`stock.read`) and links to an adjustment (`stock.adjust`); `/stock/opening-stock/new` (product search, lot number, expiry as `JJ/MM/AAAA`, `AAAA-MM-JJ` or `MM/AAAA`, quantity in a unit, unit cost prefilled from the purchase price, expired-lot warning; while `openingStockOpen` from the public settings is false the page only explains that stock found now goes through an inventory count); the integrity banner under the top bar for `audit.read` holders, linking to `/stock/integrity`; product list stock columns and "in stock only"; stock in the substitutes; the low-stock dashboard widget (out / low counts linking to `/stock?status=…`).

### 4.4 Inventory count
- Open count (full, or cycle by category or product list, ADR-073) → entry grid (lot, balance at opening, counted, balance when counted, variance, movements since counted, line status). Keyboard: Enter moves to the next counted cell. Variance highlighted. Saving a cell takes a new watermark (ADR-072). "Add a lot" for a lot of the scope without a line or a lot unknown to the system (ADR-074). "Regularise" disabled without `inventory.validate`; confirmation modal summarises net quantity per product; a refusal (`STOCK_INSUFFICIENT`) names the line and keeps the count open (ADR-075).
- **Delivered in Phase 4 (slice `phase-4/frontend-counts-reorder`):** `/inventory-counts` (status filter, progress counted / lines), `/inventory-counts/new` (full, or cycle by category or by a product list chosen through the product search), `/inventory-counts/:id`: the grid saves a counted cell on Enter or when leaving it (the same value is not sent twice), Enter moves to the next cell, refused values show on their line; variance and movements since the count with a +/− sign; "Add a lot" dialog (a lot without a line, or a lot unknown to the system with expiry `JJ/MM/AAAA`, `AAAA-MM-JJ` or `MM/AAAA`); "Regularise" disabled without `inventory.validate`, its dialog sums the variances per product exactly (decimal strings) and, on `STOCK_INSUFFICIENT` or `LOT_EXPIRY_MISMATCH`, names the refused line and highlights it while the count stays open; "Cancel the count" (`inventory.validate`); a closed count is read-only. `/reorder`: pending by default, "Generate now" with created/updated counts and links to the products with incomplete thresholds (no max stock) and, apart, inconsistent thresholds (max stock ≤ reorder point), "Dismiss" per pending proposal (`reorder.manage`). Dashboard: pending-proposals widget (`reorder.read`). The closed opening-stock page links to a new count. e2e J8 covers opening stock → cycle count → regularisation → lot ledger.
- **Delivered in Phase 5 (slice `phase-5/frontend-suppliers-orders`):** `/suppliers` (search, active filter, active by default), `/suppliers/new` and `/suppliers/:id` (edit with the version, a duplicate name on the name field, deactivate/reactivate, price history newest first with the origin — manual, receipt, cancelled receipt struck through — and a manual entry with `supplier.manage`); `/purchase-orders` (status and supplier filters), `/purchase-orders/new` and `/:id/edit` (active suppliers only; the supplier is chosen first so each new line's price, discount and VAT are prefilled from `/api/purchase-orders/prefill`, again when the unit changes; a product once per order; no amount is sent), `/purchase-orders/:id` (server amounts per line and totals, received and outstanding, status timeline, receipts, the actions of the status with `purchase.validate`; cancel and close ask for a reason in a dialog). Menu entries Suppliers and Purchase orders.
- **Delivered in Phase 5 (slice `phase-5/frontend-receipts-returns`):** `/goods-receipts` (status and supplier filters), `/goods-receipts/new?orderId=` from a sent or partially received order (lines prefilled with the outstanding base quantity, "Another lot" splits an order line) or free (active supplier, products searched): lot number, expiry (`JJ/MM/AAAA` or `MM/AAAA`), unit, quantity, cost per base unit, conforming; an expired conforming line is flagged; one `Idempotency-Key` per receipt being entered (kept when the user confirms a lot blocking), the button disabled in flight; `LOT_BLOCK_CONFIRMATION_REQUIRED` lists each lot and its stock with "Block these lots and confirm"; `/goods-receipts/:id` (lots linked, conformity and blocking, cancelled state and reason; "Cancel the receipt" with a reason, a refusal naming the lot and the movement that came after). "Receive" on a sent or partially received order. `/supplier-returns`, `/new` (supplier, reason, lots found by number with stock in any status, base quantities; `OTHER_SUPPLIER` on its line), `/:id`. `/reorder`: pending proposals selected and converted into one draft for a chosen supplier, with links to the drafts. The opening-stock page no longer knows `COST_MISMATCH` (ADR-098). e2e J3: supplier → product → PO validated and sent → partial receipt into a new lot → lot stock and ledger → order partially received → receipt cancelled, stock back to 0 (runs with J8, after the journeys expecting no lot).

### 4.5 Purchasing
- **Suppliers:** list (search, active filter), detail and edit (name unique, kind, optional tax id, payment terms), deactivate/activate, price history per product with a manual entry (ADR-100); entries of a cancelled receipt are shown struck through (ADR-106).
- **PO editor:** supplier picker (active only), line grid (product search, unit, quantity, price per ordered unit prefilled from the supplier's latest price or the product's purchase price, discount, VAT prefilled from the product, ADR-094/099/105), server-computed totals per line (ADR-093), status timeline and action buttons per status (validate/send/cancel; close with a reason when partially received, ADR-096). A product appears once per order.
- **Goods receipt:** start from a sent or partially received PO (pre-fills outstanding quantities) or a free receipt; per line **lot number and expiry date are required** (date or `MM/YYYY`, BR-LOT-001), unit, quantity at most outstanding, unit cost per base unit, conforming checkbox. A non-conforming line whose lot already has stock shows, before confirming, how many units already in stock will be blocked and asks for an explicit confirmation (ADR-095). The confirm button sends an `Idempotency-Key` and is disabled while in flight. An already expired conforming line is flagged before sending.
- **Receipt detail:** lines with lots and movements; a cancelled receipt is shown as cancelled; "Cancel receipt" (`receipt.cancel`) asks for a reason; a refusal names the lot and the later movement (ADR-103).
- **Supplier return:** supplier, lots of that supplier (any status), quantities and reason (ADR-101).
- **Reorder:** select pending proposals, choose a supplier per group, convert to draft orders (ADR-102).
- **Supplier invoice & payment:** Phase 9.
### 4.6 Customers
- List + create/edit; detail page tabs: Overview (balance, limit, available credit), Statement (date range, print), Prices, Invoices & payments, History. Credit-limit editing requires `customer.credit.manage`; balance definition per OD-003.

### 4.7 Point of sale (`/pos`)
Layout: left = product search + scan field + quick results; centre = cart lines (lot + expiry visible, warnings inline); right = customer, totals, payment, actions.

| Behaviour | Detail |
|---|---|
| Add item | scan/enter → `POST /sales/{id}/lines`; a draft document is created lazily on first line |
| Lot allocation | shown per line (lot, expiry); a quantity spanning lots appears as several lines grouped visually |
| Warnings | near-expiry, withdrawal period (animal-species context), low stock after sale — shown as `Alert` banners/chips; never block |
| Override FEFO | visible only with `sale.override_fefo`; opens lot picker **[OPEN OD-010]** |
| Customer | optional for cash; mandatory for credit (`CUSTOMER_REQUIRED_FOR_CREDIT`); credit-limit state shown |
| Confirm | one button, disabled while request in flight, sends `Idempotency-Key`; on `STOCK_INSUFFICIENT`/`LOT_*` the offending line is highlighted and refreshed from `context` |
| After confirm | success screen with number, print A4/ticket (OD-020), "new sale" |
| Offline | not supported (no offline requirement in the Spec `[REC]`); a banner blocks sale when API unreachable |
| Cash session | if no open session and payments need one → modal to open it (`CASH_SESSION_REQUIRED`) |

### 4.8 Sales documents and returns
- Document list with type/status badges; detail with lines, totals, payments, linked documents, PDF buttons. Convert actions (quote → DN → invoice). Cancel only if OD-004 allows, with reason modal.
- **Customer return wizard:** pick invoice → select lines/quantity (max = sold − already returned) → reason, sealed?, disposition (restock / discard per OD-005) → review → submit. Shows resulting credit note.
- **Supplier return:** pick lot(s) and quantities, reason.

### 4.9 Payments and cash
- Customer payment dialog: method, amount, reference (mandatory for cheque/transfer), allocation grid pre-filled oldest-first and editable; shows unallocated remainder (policy OD-003). Reversal requires reason + `payment.reverse`.
- Cash: open session (amount), live expected total, manual movements, close with counted amount, variance report.

### 4.10 Reports
Common filter bar (period, grouping), table + simple chart, export CSV/PDF. Role-restricted per report; margin/valuation visible only with their permissions.

### 4.11 Administration
Users (create, deactivate, reset password), role/permission matrix editor (checkbox grid, diff summary before save), settings form (typed inputs, validation per key), audit log viewer (filters, JSON detail drawer, read-only), backups list/run/download.

## 5. Components (design-system mapping)

Derived from the prototype; implement as typed, accessible components in `components/`.

| Component | Notes |
|---|---|
| Button (primary/secondary/danger/ghost, sizes, loading) | prevents double submit |
| Input / Select / Combobox / DatePicker / DecimalInput | `DecimalInput` keeps string value, locale-aware display |
| PasswordInput | every password field (login, change password, initial password, temporary password after a reset). Hidden by default; an eye `<button type="button">` per field, never submitting, keyboard-reachable, `aria-pressed`, translated name "Show / Hide password", visible focus ring, placed on the end side (RTL-aware). Hidden again on submit (successful or not), when focus leaves the field and when the page is left. Option `copyable` (temporary password after a reset): a translated "Copy password" button copies the value without revealing it; a short confirmation ("Password copied.") or failure message, which never contains the value, is announced through `role="status"` and disappears after 3 s. Keeps the label and `autocomplete` (`current-password` / `new-password`); the value lives only in the input: never logged, stored, put in the URL or in `localStorage`. Tokens only. |
| Dialog | modal with `role="dialog"`, `aria-modal`, labelled by its title; focus moves to the first control on open and stays inside with Tab / Shift+Tab; Escape closes and the focus returns to the opener. Used by the lot actions (Phase 3). Tokens only. |
| Table | sortable headers, sticky header, row actions, empty/loading skeleton, keyboard navigation |
| Badge / StatusChip | status → colour **plus** text/icon |
| Modal / ConfirmDialog | focus trap, `Esc`, returns focus |
| Toast | success/info only; errors that need action use inline `Alert` |
| Alert | **`role="alert"`** for errors/warnings that appear dynamically (fix of prototype gap, DR-014) |
| Tabs, Breadcrumb, Pagination, FilterBar, FormField (label + hint + error) | |
| ProductSearch (async combobox), BarcodeInput, LotPicker, ExpiryBadge, QuantityWithUnit | domain composites |
| PermissionGate | hides children without permission (comfort only) |

## 6. Design tokens

- Tokens from the prototype live in `src/styles/tokens.css` as RGB channels (`--bg`, `--surface`, `--line`, `--ink`, `--mute`, `--brand`, `--brand-ink`, `--ok`, `--warn`, `--danger`) and are wired into the Tailwind theme in `src/styles/index.css` (`bg-surface`, `text-ink`, `border-line`, … with opacity modifiers), together with `--radius-ctl`, `--radius-card` and `--font-sans`.
- **Dark theme tokens exist.** The prototype defines a complete dark set and `tokens.css` reproduces it value for value (checked 2026-10-22): light on `:root`; dark applied by `@media (prefers-color-scheme: dark)` on `:root:not([data-theme='light'])`, or forced by `data-theme="dark"` on `<html>` (`data-theme="light"` forces light). Today the app follows the operating-system setting only: nothing sets `data-theme` and there is no theme switch (the prototype's "Theme" button is a demo control). The user switch, its persistence and the verification are **Phase 13** (ADR-069).
- **Rule for Phases 1–12 (ADR-069):** components use design tokens only — Tailwind token classes or `rgb(var(--token))` — never hard-coded colours (hex, `rgb(…)` literals, Tailwind palette classes such as `text-red-600`, inline colour styles). New semantic colours are added as tokens with a light **and** a dark value. This keeps Phase 13 free of screen rewrites.
- **Contrast (WCAG 2.1 AA), fixed 2026-10-22 and guarded by `src/styles/contrast.test.ts`** over every token pair the components use, in both themes (text 4.5:1; control borders, focus ring and invalid border 3:1). Changes from the prototype: a new **`--control`** token borders inputs, selects and buttons (light `128 142 147`, dark `96 114 120`: 3.17 / 3.38 light, 3.70 / 3.34 dark against bg / surface) while `--line` stays for decorative dividers and table rules; light **`--ok`** `21 120 70` and **`--warn`** `162 84 7` are slightly darker than the prototype (text on bg 5.18 / 5.17, on surface 5.52 / 5.52, on their 10 % tint over bg 4.52 / 4.53 and over surface 4.82 / 4.80); dark values are unchanged (all text pairs ≥ 6.58). Before the fix, control borders were 1.23–1.62, light `warn` on bg 4.45 and the light `ok`/`warn` tints 3.91–4.35.
- **Alert borders are decorative:** `ok/40` and `danger/40` around an alert (about 1.8–2.4:1) are not required to reach 3:1, because the alert's text and tinted background carry the meaning; they are excluded from the contrast test on purpose.
- **Other gaps (DR-014):** the prototype shows currency in EUR and generic address fields, while the Spec targets Algeria — resolved by **ADR-033** (DZD, fr-DZ); small-text sizes need verification; statuses must not rely on colour alone.
- Expiry-alert palette (expired / critical / warning / notice) and stock-status palette are defined as semantic tokens, not raw colours.

## 7. Forms and validation

- Zod schema per form; field errors from the server (`errors[].field`) are mapped back onto the matching field (nested paths like `lines[0].lotNumber`).
- Client rules mirror obvious server rules (required, positive quantity, max length, date formats) to give instant feedback; **business rules (stock, credit, FEFO) are only surfaced from server responses**.
- Submit guards: disable while pending; on success invalidate the relevant query keys; on `CONCURRENT_MODIFICATION` offer reload with diff.
- Unsaved-changes prompt on navigation.

## 8. Data fetching and state

| State kind | Where |
|---|---|
| Server data | TanStack Query; keys `[domain, resource, params]`; stale times: reference data long, stock/lots short (≈10 s), POS cart never cached stale |
| Auth/session | small store (current user, permissions) hydrated from `/api/auth/me`; session cookie set by the server (ADR-036); the SPA never sees or stores a credential; `X-XSRF-TOKEN` header from the `XSRF-TOKEN` cookie on every state-changing request |
| UI state | local component state; URL holds filters/pagination/sort so pages are shareable and refresh-safe |
| Global client state | avoided; no Redux |

Mutations: after stock-affecting actions invalidate `stock`, `lots`, `products` queries.

## 9. Error handling

| Situation | UX |
|---|---|
| 400 `VALIDATION_FAILED` | field errors + summary |
| 401 | session expired: clear state and redirect to login with return URL |
| 403 `ACCESS_DENIED` | inline "not allowed" message; no redirect loop |
| 404 | not-found state in place |
| 409 conflicts | dedicated messages: stale version dialog, state-transition message, duplicate field error |
| 422 business | human message from `detail`, specific highlighting via `code`/`context` |
| 429 | message with retry delay |
| 5xx / network | generic error with `traceId` shown for support, retry button |

All messages go through a code → localised-text map (the UI chooses wording; `detail` is a fallback).

## 10. Internationalisation and formatting

Per **ADR-033**: UI languages French (default), Arabic (RTL) and English via i18next/react-i18next; every string externalised in `src/lib/i18n/locales/{fr,ar,en}.json`; `<html lang dir>` follows the active language; layout uses logical properties only (Tailwind `ms-`/`me-`/`ps-`/`pe-`/`start-`/`end-`). Numbers, dates and currency (DZD) are formatted with `Intl` using the `fr-DZ` locale for every language, times on a 24-hour clock (ADR-033); UTC instants are displayed in `Africa/Algiers`. Currency, locale and time zone come from `GET /api/settings/public` after login (ADR-044).

## 11. Responsive behaviour

Desktop-first (counter and back-office). Breakpoints: ≥1280 full layout; 768–1279 collapsible sidebar, tables scroll horizontally with sticky first column; <768 supported for read-only lookups (stock, lot, customer) and basic receiving; POS is designed for desktop/tablet landscape (assumption).

## 12. Accessibility

Target WCAG 2.1 AA: semantic landmarks, labelled controls, visible focus, keyboard-only POS flow, `role="alert"` / live regions for dynamic errors, sufficient contrast, status not colour-only, modals trap focus, table headers with scope, form errors linked via `aria-describedby`, reduced-motion respect.

## 13. Printing and documents

Server generates PDFs (`/api/sales/{id}/pdf`, reports); the UI opens them in a new tab / print dialog. Formats (A4 invoice, thermal ticket) and engine per **OD-020**. The UI never composes legal documents itself. Print stylesheets only for simple lists (e.g. lot recall buyers).

## 14. Security (frontend side)

No secrets in the bundle; tokens never in `localStorage`; no `dangerouslySetInnerHTML` with API data; CSP-compatible (no inline eval); CSRF header on every state-changing request (ADR-036); `PASSWORD_CHANGE_REQUIRED` redirects to the change-password page; logout clears query cache; permission codes treated as UI hints only.

## 15. Performance

Route-level code splitting; virtualised long tables (stock, ledger, count grid); debounced search (≈250 ms) with request cancellation; image-free UI; target first useful paint < 2 s on LAN (NFR-002).

## 16. Testing (summary; see `testing.md`)

Component tests for primitives and forms (RTL), API-mocked page tests (MSW) including every error code path, Playwright journeys: login, receive goods, POS sale (FEFO/expired/insufficient), return, payment, count, recall.

## 17. Open frontend items

OD-002/003 (POS confirm payload), OD-010 (FEFO override UI), OD-020 (print formats); DR-014 (theme switch, Phase 13), DR-015 (hard-coded thresholds).
