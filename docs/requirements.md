# Functional Requirements — IMS Vétérinaire

> **Status:** Documentation / Architecture Phase — Implementation Not Started
> **Source of truth:** `said_project.md` — *Cahier des charges fonctionnel*, 12 October 2026 ("the Spec").
> **Secondary reference:** `ims_veterinaire_schema_v2.sql` (PostgreSQL 14+ design, "the Source Schema").
> **Last updated:** 2026-10-18

This document restates every requirement of the Spec in a structured, numbered form so that each can be traced to business rules (`business-rules.md`), tables (`database.md`), endpoints (`api.md`), tests (`testing.md`) and roadmap phases (`roadmap.md`).

Conventions used throughout the documentation set:

| Tag | Meaning |
|---|---|
| `[SRC]` | Stated in the Spec (the functional specification). |
| `[SCH]` | Derived from the Source Schema, not stated in the Spec. |
| `[REC]` | Architectural recommendation by the documentation author. Not a source requirement. |
| `OD-nnn` | OPEN DECISION registered in `decisions.md`. Must be decided by the project owner; a coding LLM must not choose. |
| `FR-<MOD>-nnn` | Functional requirement identifier. |
| `NFR-nnn` | Non-functional requirement identifier. |

---

## 1. Business context and objectives

IMS Vétérinaire is an integrated management system (stock, purchasing, sales, finance) for **pharmacies and distributors of veterinary medicines** `[SRC §1]`.

Business objectives stated by the Spec:

1. **Health compliance** — withdrawal periods, expiry control, lot recall.
2. **Product traceability** — every unit sold can be traced back to a received lot (upstream) and every lot to its buyers (downstream).
3. **Financial profitability** — margins, valued stock, receivables (breeder credit accounts), payables.

## 2. Actors

| Actor | Type | Description | Source |
|---|---|---|---|
| Administrator | System user (role) | Full access: users, roles, settings, audit, backups. | `[SRC §2.7]`, `[SCH roles seed]` |
| Pharmacist | System user (role) | Stock, lots, recalls, pricing (Source Schema role description). | `[SRC §2.7]`, `[SCH]` |
| Seller (Vendeur) | System user (role) | Counter/POS sales and customer accounts. | `[SRC §2.7]`, `[SCH]` |
| Accountant (Comptable) | System user (role) | Finance, payments, reports. | `[SRC §2.7]`, `[SCH]` |
| Breeder (Éleveur) | External — Customer | Buys medicines; often on credit; subject to withdrawal-period warnings. | `[SRC §2.5, §2.6]` |
| Veterinarian | External — Customer / follow-up vet | A customer of type `veterinarian`; may be the follow-up vet of a breeder. | `[SRC §2.5]`, `[SCH]` |
| Supplier | External | Laboratory or wholesaler. | `[SRC §2.4]` |
| System scheduler | Automated | Expiry alerts, reorder proposals, backups. | `[SRC §2.2, §2.3, §2.7]` |

The Spec names the four roles but states only that they have "restricted rights". The permission matrix was therefore `[REC]` and has been accepted by the project owner (`security.md` §4, ADR-037).

## 3. Assumptions and constraints

| ID | Statement | Basis |
|---|---|---|
| A-001 | Single pharmacy / single site / single stock location. No multi-warehouse, no multi-tenant. | Not in Spec; schema has no location/tenant concept `[SCH]`. |
| A-002 | Single currency: Algerian dinar (DZD), formatting locale `fr-DZ`, business time zone `Africa/Algiers` (**ADR-033**, was OD-008). | Spec silent; schema comments mention *wilaya*, *commune*, *matricule fiscal / NIF*, default VAT 9 % `[SCH]`; design system sample data uses EUR. |
| A-003 | All stock quantities are stored in the product's **base unit** (smallest sellable unit). Packaging units convert by a factor. | `[SCH]` header comment; satisfies `[SRC §2.1]` multi-unit requirement. |
| A-004 | Stock is tracked **per lot**. Product-level stock is a derived aggregate. | `[SRC §2.2]`, `[SCH]`. |
| A-005 | Barcode scanners behave as keyboard-wedge (HID) devices: they "type" the code followed by Enter. | `[REC]` — Spec only says "support du lecteur code-barres". |
| A-006 | The application is used on a local network or internet-hosted server by a small number of concurrent users (counter staff + back-office). No offline mode. | `[REC]` — Spec silent (**OD-021**). |
| A-007 | Stack: React + Vite, Java + Spring Boot + Maven, MySQL, REST, Spring Data JPA/Hibernate, Git, Linux dev. | User brief (ADR-001…005). |
| A-008 | The Spec's UI/document language is French. UI languages: French (default), Arabic (RTL), English (**ADR-033**, was OD-008). | Spec is in French; design system strings are English. |

---

## 4. Module requirements

Each module follows the template: Objective · Actors · Features · Inputs · Outputs · Validations · Business rules · Dependencies.
Module numbering follows the Spec (§2.1 … §2.7). Module codes: `PROD`, `LOT`, `STOCK`, `PURCH`, `SALE`, `FIN`, `SEC` (+ cross-cutting `AUD`, `RPT`).

---

### Module 1 — Product Catalogue & Reference Data (`PROD`) — Spec §2.1

**Objective.** Maintain the catalogue of veterinary medicines with all clinical, packaging and identification data needed by stock, sales and compliance.

**Actors.** Administrator, Pharmacist (write); Seller, Accountant (read).

**Features**

| ID | Requirement | Basis |
|---|---|---|
| FR-PROD-001 | Create, modify, consult and **archive** product sheets (fiches médicaments). Archived products are hidden from sale/purchase but remain in history. | `[SRC]` |
| FR-PROD-002 | Record per product: commercial name, dosage form (injectable, powder, tablet, solution), therapeutic category (antibiotic, antiparasitic, vaccine…), manufacturer. | `[SRC]` |
| FR-PROD-003 | Assign a unique identifier (**SKU**) and manage **barcodes / QR codes** (several per product). | `[SRC]` |
| FR-PROD-004 | Index products by **active ingredient (DCI / INN)** to allow search and fast **substitution** when a product is out of stock. | `[SRC]` |
| FR-PROD-005 | Record **withdrawal periods** (meat, milk, eggs) per animal species so the legal warning can be printed on sales documents for breeders. | `[SRC]` |
| FR-PROD-006 | Support selling by **packaging unit** (box, bottle) or **sub-unit** (blister, syringe, dose/ml). | `[SRC]` |
| FR-PROD-007 | **Automatic stock conversion** on retail sale (e.g. a 100 ml bottle sold in 5 ml doses). | `[SRC]` |
| FR-PROD-008 | Maintain reference lists: manufacturers, therapeutic categories, dosage forms, active ingredients, animal species. | `[SCH]` (implied by FR-PROD-002/004/005) |
| FR-PROD-009 | Per product: VAT rate, purchase price, sale price (per base unit), minimum stock, maximum stock, reorder point. | `[SCH]`, `[SRC §2.3]` |

**Inputs.** Product data, barcode values, ingredient links with strength, withdrawal days per species, unit definitions (name, factor to base unit, optional unit sale price, sellable flag).
**Outputs.** Product sheets, search results, substitution lists (same active ingredient), product stock summary.
**Validations.** SKU unique; barcode unique across all products; base unit mandatory; prices ≥ 0; `max_stock ≥ min_stock`; unit factor > 0; unit name unique per product; withdrawal days ≥ 0.
**Business rules.** BR-PROD-001…007, BR-UNIT-001…004.
**Dependencies.** None (foundation). Consumed by every other module.

---

### Module 2 — Lot Traceability & Expiry (`LOT`) — Spec §2.2

**Objective.** Guarantee sanitary traceability from supplier receipt to customer sale, enforce expiry, and support lot recall.

**Actors.** Pharmacist (block/recall); Seller (FEFO consumption at sale); System scheduler (alerts); Administrator.

**Features**

| ID | Requirement | Basis |
|---|---|---|
| FR-LOT-001 | Manufacturer **lot number is mandatory** on every stock entry. | `[SRC]` |
| FR-LOT-002 | Every sale line is associated with its **lot number** (upstream and downstream traceability). | `[SRC]` |
| FR-LOT-003 | **Expiry date** is captured at receipt. | `[SRC]` |
| FR-LOT-004 | **FEFO** (First Expired, First Out): the system automatically proposes the lot with the nearest expiry date for each outgoing quantity. | `[SRC]` |
| FR-LOT-005 | **Configurable visual alerts** for products nearing expiry (e.g. 60, 30, 15 days). | `[SRC]` |
| FR-LOT-006 | **Automatic blocking of the sale** of expired medicines. | `[SRC]` |
| FR-LOT-007 | **Lot recall procedure**: instantly block a specific lot and generate the **list of customers** who bought it. | `[SRC]` |
| FR-LOT-008 | Lot lifecycle states: active, blocked, recalled, expired, depleted. | `[SCH]` |

**Inputs.** Lot number, expiry date, supplier, unit cost (from goods receipt); recall reason; alert thresholds (setting).
**Outputs.** FEFO-ordered lot list per product, expiry alert list by level (expired / ≤15 / ≤30 / ≤60 days), recall report (customer, phone, invoice number, date, quantity).
**Validations.** Lot number non-empty; expiry date present; `(product, lot number)` unique, a different expiry for an existing lot number refused (ADR-070); sale refused for expired/blocked/recalled lots.
**Business rules.** BR-LOT-001…012.
**Dependencies.** PROD; produced by PURCH (goods receipt) and STOCK (opening stock); consumed by SALE.

---

### Module 3 — Stock, Thresholds & Replenishment (`STOCK`) — Spec §2.3

**Objective.** Keep an exact, auditable, real-time stock position; trigger replenishment; handle manual adjustments and physical inventory.

**Actors.** Pharmacist, Administrator (write); Seller (read); System scheduler.

**Features**

| ID | Requirement | Basis |
|---|---|---|
| FR-STOCK-001 | **Instant stock update** on every operation: purchase, sale, return, loss. | `[SRC]` |
| FR-STOCK-002 | **Unalterable history** of all stock movements with timestamp and operator identifier. | `[SRC]` |
| FR-STOCK-003 | Configure **minimum**, **maximum** and **reorder point** per product (safety stock). | `[SRC]` |
| FR-STOCK-004 | **Automatically generate purchase-order proposals** when the alert threshold is reached. | `[SRC]` |
| FR-STOCK-005 | Record **manual adjustments** (breakage, expiry, theft, internal use) with **mandatory justification**. | `[SRC]` |
| FR-STOCK-006 | **Physical inventory** (full or cycle/rolling): compare theoretical to real stock and **regularize variances**. | `[SRC]` |
| FR-STOCK-007 | Opening-stock entry for initial load of existing stock. | `[SCH]` (`opening_stock` movement type) |

**Inputs.** Stock operations from other modules; adjustment (lot, quantity, type, reason); inventory counts per lot.
**Outputs.** Stock levels per product/lot, movement history, reorder proposals, inventory variance report.
**Validations.** Stock never negative; adjustment reason non-blank; counted quantity ≥ 0; movement quantity ≠ 0.
**Business rules.** BR-STOCK-001…012, BR-INV-001…007.
**Dependencies.** PROD, LOT; written by PURCH, SALE, returns.

---

### Module 4 — Purchasing & Supplier Relations (`PURCH`) — Spec §2.4

**Objective.** Manage suppliers, purchase orders, goods receipt and supplier returns.

**Actors.** Pharmacist (orders, receipts, returns); Accountant (read); Administrator.

**Features**

| ID | Requirement | Basis |
|---|---|---|
| FR-PURCH-001 | Directory of **laboratories and wholesalers**: contact details, tax identifier (*matricule fiscal*), payment terms. | `[SRC]` |
| FR-PURCH-002 | Track **historical purchase prices** and **discounts** granted per supplier. | `[SRC]` |
| FR-PURCH-003 | Edit **purchase orders** with automatic calculation of **HT, VAT and TTC** amounts. | `[SRC]` |
| FR-PURCH-004 | Track purchase-order states: **Draft, Validated, Sent, Partially received, Closed (Soldé)**. | `[SRC]`; `cancelled` added by `[SCH]` |
| FR-PURCH-005 | **Goods receipt** interface: verify quantities, enter lots/expiry dates, check **conformity against the purchase order**. | `[SRC]` |
| FR-PURCH-006 | **Supplier returns** for defective or non-conforming goods. | `[SRC]` |
| FR-PURCH-007 | Reorder proposals can be converted into purchase orders. | `[SCH]` (`reorder_proposals.status = converted`) |

**Inputs.** Supplier data, price/discount entries, PO lines (product, unit, quantity, price, discount, VAT), receipt lines (lot number, expiry, quantity, cost, conformity), return lines (lot, quantity, reason).
**Outputs.** PO documents (printable), goods-receipt records, stock increases, supplier-return records.
**Validations.** Quantities > 0; discount 0–100 %; receipt lines carry lot number and expiry; supplier return ≤ lot quantity on hand.
**Business rules.** BR-PURCH-001…011, BR-DOC-001.
**Dependencies.** PROD, LOT/STOCK (posting), FIN (supplier invoices & payables).

---

### Module 5 — Sales, Counter (POS) & Customers (`SALE`) — Spec §2.5

**Objective.** Provide fast counter sales and full sales documentation, customer management and customer returns.

**Actors.** Seller (POS), Pharmacist (cancellations, returns), Administrator; Accountant (read).

**Features**

| ID | Requirement | Basis |
|---|---|---|
| FR-SALE-001 | **Fast sales screen** suited to the counter, with **barcode scanner** support. | `[SRC]` |
| FR-SALE-002 | **Multi-criteria search**: commercial name, DCI, category, animal species. | `[SRC]` |
| FR-SALE-003 | Issue **invoices, receipts (tickets de caisse), delivery notes and quotes**, printable or exportable as PDF. | `[SRC]` |
| FR-SALE-004 | Customer database: **farming type, location (wilaya/commune), follow-up veterinarian**. | `[SRC]`, `[SCH]` |
| FR-SALE-005 | Apply **preferential prices or personalised discounts** by customer category. | `[SRC]` |
| FR-SALE-006 | **Customer returns**: re-enter stock if sealed, **discard** if opened or expired. | `[SRC]` |
| FR-SALE-007 | **Automatic credit notes** on customer returns. | `[SRC]` |
| FR-SALE-008 | Display/print **withdrawal-period warnings** on sales documents for breeders. | `[SRC §2.1]` (rendered in this module) |
| FR-SALE-009 | Document chain: quote → delivery note → invoice; invoice → credit note. | `[SCH]` (`parent_id`) |
| FR-SALE-010 | Per-customer product prices (`customer_product_prices`). | `[SCH]` |

**Inputs.** Customer, product/unit/quantity, target species, discount, payment mode, credit flag, return lines with disposition.
**Outputs.** Sales documents (PDF / thermal ticket), stock decrease with lot assignment, credit notes.
**Validations.** Lot assigned at confirmation; credit sale requires a known customer; discount 0–100 %; return ≤ quantity sold.
**Business rules.** BR-SALE-001…016, BR-CUST-001…004, BR-RET-001…006, BR-DOC-001.
**Dependencies.** PROD, LOT, STOCK (posting), FIN (credit limit, payments, cash session).

---

### Module 6 — Financial Tracking, Receivables & Settlements (`FIN`) — Spec §2.6

**Objective.** Track what customers owe (breeder accounts), what is owed to suppliers, and cash movements.

**Actors.** Accountant (primary), Seller (counter payments), Administrator.

**Features**

| ID | Requirement | Basis |
|---|---|---|
| FR-FIN-001 | Record **credit sales** ("très courantes dans le secteur agricole"). | `[SRC]` |
| FR-FIN-002 | **Real-time customer balance** and **credit-limit ceiling** (*plafonnement de l'encours*). | `[SRC]` |
| FR-FIN-003 | **Detailed account statements** showing purchase and payment history. | `[SRC]` |
| FR-FIN-004 | Record **supplier invoices** and track **payment due dates**. | `[SRC]` |
| FR-FIN-005 | Track **advances paid** (acomptes) and **supplier credit notes** (avoirs). | `[SRC]` |
| FR-FIN-006 | Record **receipts and disbursements** by method: **cash, cheque, bank transfer**. | `[SRC]` |
| FR-FIN-007 | **Cash sessions**: opening, closing, counting, **cash variances** (écarts de caisse). | `[SRC]` |
| FR-FIN-008 | A payment can be allocated to one or several invoices. | `[SCH]` (`payment_allocations`) |

**Inputs.** Payments (customer, method, amount, reference), allocations, supplier invoices, supplier payments, cash session open/close figures.
**Outputs.** Customer balances and statements, supplier payables, cash session report with variance.
**Validations.** Amount > 0; allocation ≤ payment and ≤ invoice outstanding; credit limit respected; one open cash session per cashier.
**Business rules.** BR-PAY-001…011, BR-CASH-001…005.
**Dependencies.** SALE (invoices, credit notes), PURCH (receipts), STOCK (none).

---

### Module 7 — Security, Administration & Reporting (`SEC`, `AUD`, `RPT`) — Spec §2.7

**Objective.** Secure access, trace sensitive actions, protect data, and provide management reporting.

**Actors.** Administrator (security/admin), Accountant & Pharmacist (reports).

**Features**

| ID | Requirement | Basis |
|---|---|---|
| FR-SEC-001 | **Secure authentication** by username and password. | `[SRC]` |
| FR-SEC-002 | **Role-based access** with restricted rights (Administrator, Pharmacist, Seller, Accountant). | `[SRC]` |
| FR-AUD-001 | **Complete audit trail** of sensitive actions: price changes, invoice cancellations, stock corrections. | `[SRC]` |
| FR-AUD-002 | **Export and automatic backup** of the database. | `[SRC]` |
| FR-RPT-001 | Reports on **revenue** and **gross margin** by category or product. | `[SRC]` |
| FR-RPT-002 | **Valued stock statement** at purchase cost or at sale price. | `[SRC]` |
| FR-RPT-003 | **Sales statistics** by period, by breeder, by geographic zone. | `[SRC]` |
| FR-SEC-003 | Configurable settings (expiry alert thresholds, default VAT). | `[SCH]` (`app_settings`) |

**Inputs.** Credentials, role/permission assignments, report filters (period, product, category, customer, wilaya).
**Outputs.** Session/token, audit entries, backup files + log, report tables/exports.
**Validations.** Active user only; password stored hashed; every sensitive action audited.
**Business rules.** BR-SEC-001…006, BR-AUDIT-001…003, BR-RPT-001…003, BR-BKP-001…002.
**Dependencies.** All modules (audit hooks and read models).

---

## 5. Non-functional requirements

| ID | Requirement | Basis | Notes |
|---|---|---|---|
| NFR-001 | **Data integrity:** stock can never be negative; invoice/order numbering is strictly unique. | `[SRC §3.1]` | BR-STOCK-001, BR-DOC-001 |
| NFR-002 | **Ergonomics:** instant search (**< 1 second**) so counter service is not slowed. | `[SRC §3.2]` | **ADR-048** (search technique, 50,000-product dataset) |
| NFR-003 | **Printing:** compatible with **thermal printers** (tickets) and standard printers (**A4 PDF**). | `[SRC §3.3]` | **OD-020** (printing approach) |
| NFR-004 | Heading "Disponibilité et Impression" implies availability matters, but **no availability figure is given**. | `[SRC §3.3]` gap | **OD-021** |
| NFR-005 | Passwords hashed (bcrypt/argon2); never stored in clear. | `[SCH]` | BR-SEC-003 |
| NFR-006 | Backend is the authoritative security boundary. | User brief | `security.md` |
| NFR-007 | Business-critical rules are enforced in the backend, never only in React. | User brief | ADR-007 |
| NFR-008 | Schema must run on MySQL; PostgreSQL-specific features are adapted, not copied. | User brief | `database-migration.md` |

## 6. Not specified / out of scope (explicitly not to be invented)

The Spec does not describe the following. They must **not** be implemented unless added through a documented decision:

- Multi-site / multi-warehouse stock, inter-site transfers.
- Multi-currency, exchange rates.
- Prescription (ordonnance) management, regulated-substance registers, patient/animal records.
- Loyalty programs, promotions engine beyond category discount and per-customer prices.
- Accounting ledger / general-ledger export; tax declaration forms.
- E-commerce, customer portal, mobile app, offline mode.
- Direct ESC/POS printer drivers (see **OD-020**).
- Email/SMS notifications (alerts are *visual*, `[SRC §2.2]`).
- Barcode generation/printing of labels.

## 7. Requirement ↔ module summary

| Module | Code | FR count | Roadmap phase(s) |
|---|---|---|---|
| Product catalogue & reference data | PROD | 9 | 2 |
| Lot traceability & expiry | LOT | 8 | 3, 4 |
| Stock, thresholds & replenishment | STOCK | 7 | 4 |
| Purchasing & suppliers | PURCH | 7 | 5 |
| Sales / POS / customers | SALE | 10 | 6, 7, 8, 10 |
| Finance | FIN | 8 | 9 |
| Security / audit / reports | SEC, AUD, RPT | 3 + 2 + 3 | 1, 11, 12 |

The full requirement → phase matrix is in `roadmap.md` §3.
