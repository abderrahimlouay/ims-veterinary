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
| `/inventory-counts`, `/inventory-counts/new`, `/inventory-counts/:id` | counts, entry grid, regularise | `inventory.count` |
| `/reorder` | reorder proposals | `reorder.read` |
| `/suppliers`, `/suppliers/:id` | list, detail (prices, payables) | `supplier.read` |
| `/purchase-orders`, `/new`, `/:id` | PO list/edit/detail | `purchase.read` |
| `/goods-receipts`, `/new`, `/:id` | receipts | `receipt.read` |
| `/supplier-invoices`, `/:id` | supplier invoices & payments | `supplierinvoice.manage` |
| `/customers`, `/:id` | list, detail (balance, statement, prices, history) | `customer.read` |
| `/pos` | counter sale | `sale.create` |
| `/sales`, `/sales/:id` | documents list/detail (quote, DN, invoice) | `sale.read` |
| `/returns/customer`, `/returns/supplier` (+ `/new`) | returns | `return.read` |
| `/payments/customers`, `/payments/suppliers` | payments | `payment.read` |
| `/cash-sessions`, `/cash-sessions/:id` | sessions, report | `cash.open` / `cash.manage` |
| `/reports/*` | reports (revenue, margin, valuation, stats, balances, payables, traceability) | per report permission |
| `/admin/users`, `/admin/roles`, `/admin/settings`, `/admin/audit`, `/admin/backups` | administration | `user.read`, `role.manage`, `settings.manage`, `audit.read`, `backup.manage` |
| `/account` | profile, change password | authenticated |
| `*` | Not-found | — |

## 4. Page design by domain

For each: purpose → main elements → key behaviours → states.

### 4.1 Product catalogue and detail
- **List:** search box (name/SKU/DCI/barcode), filters (category, manufacturer, active ingredient, species **[OPEN OD-018]**, in-stock only, show archived); table columns: SKU, name, category, base unit, sale price, sellable stock with status badge, nearest expiry.
- **Detail form:** tabs per `api.md` §4.3. Price fields read-only without `price.edit`. Archive instead of delete (BR-PROD-004). Substitute panel (FR-PROD) lists same-ingredient products with stock.
- **Behaviours:** barcode field accepts scanner input (Enter-terminated); duplicate SKU/barcode shows field-level error from `PRODUCT_SKU_DUPLICATE` / `BARCODE_DUPLICATE`; `CONCURRENT_MODIFICATION` opens a "reload latest" dialog.

### 4.2 Lots and expiry
- **Lot list:** filters (product, status, expiring within N days, with stock); expiry shown with level badge (expired / critical / warning / notice) using thresholds from `/api/settings/public` (never hard-coded; DR-015 for the prototype's fixed values).
- **Lot detail:** header, balance, movement history, actions Block / Unblock / Recall (modal with mandatory reason). Recall result shows the buyers table with export/print.
- **Dashboard widget:** counts per alert level linking to filtered list.

### 4.3 Stock and adjustments
- **Stock levels:** sellable vs total, thresholds, below-min / below-reorder filters.
- **Ledger:** read-only, filter by product/lot/type/user/date; signed quantities coloured by direction **and** with +/− sign (not colour alone).
- **Adjustment form:** lot picker (shows balance), type, quantity, mandatory reason; inline preview "balance after".

### 4.4 Inventory count
- Open count → entry grid (lot, theoretical, counted, variance, line status). Keyboard: Enter moves to the next counted cell. Variance highlighted. "Regularise" disabled without `inventory.validate`; confirmation modal summarises net quantity per product. Behaviour of theoretical quantities during a live count follows **OD-012**.

### 4.5 Purchasing
- **PO editor:** supplier picker (active only), line grid (product search, unit, quantity, price, discount, VAT), server-computed totals, status timeline and action buttons that appear per status (validate/send/cancel).
- **Goods receipt:** start from PO (pre-fills outstanding quantities) or free receipt; per line **lot number and expiry date are required** (visual required marker, BR-LOT-001); over-receipt behaviour per OD-015; conformity flag per OD-014.
- **Supplier invoice & payment:** matching to receipt, due-date highlight, payment dialog with advance option.

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
| Table | sortable headers, sticky header, row actions, empty/loading skeleton, keyboard navigation |
| Badge / StatusChip | status → colour **plus** text/icon |
| Modal / ConfirmDialog | focus trap, `Esc`, returns focus |
| Toast | success/info only; errors that need action use inline `Alert` |
| Alert | **`role="alert"`** for errors/warnings that appear dynamically (fix of prototype gap, DR-014) |
| Tabs, Breadcrumb, Pagination, FilterBar, FormField (label + hint + error) | |
| ProductSearch (async combobox), BarcodeInput, LotPicker, ExpiryBadge, QuantityWithUnit | domain composites |
| PermissionGate | hides children without permission (comfort only) |

## 6. Design tokens

- Tokens from the prototype are exported once as CSS variables (`--color-*`, `--space-*`, `--radius-*`, `--font-*`) and wired into the Tailwind theme; components use only token-based classes.
- **Gaps / conflicts to resolve (DR-014):** the prototype shows currency in EUR and generic address fields, while the Spec targets Algeria (wilaya/commune, NIF, DZD) — currency/locale resolved by **ADR-033** (DZD, fr-DZ); no dark theme defined; focus ring contrast and small-text sizes need verification; statuses rely on colour in places.
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

Per **ADR-033**: UI languages French (default), Arabic (RTL) and English via i18next/react-i18next; every string externalised in `src/lib/i18n/locales/{fr,ar,en}.json`; `<html lang dir>` follows the active language; layout uses logical properties only (Tailwind `ms-`/`me-`/`ps-`/`pe-`/`start-`/`end-`). Numbers, dates and currency (DZD) are formatted with `Intl` using the `fr-DZ` locale for every language; UTC instants are displayed in `Africa/Algiers`.

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

OD-002/003 (POS confirm payload), OD-010 (FEFO override UI), OD-012 (count UX), OD-018/019 (search by species, unit barcode), OD-020 (print formats); DR-014 (design-token gaps), DR-015 (hard-coded thresholds).
