# User acceptance testing (UAT) — Phase 12

> **Purpose:** the pharmacy (and its accountant for §2) checks, on the installed server and with its own PCs and printers, that the application does its work, then signs (§6). Phase 12 is complete only with this sign-off and no open Critical or High item in `documentation-review.md` (roadmap). Run on the production server **before** go-live data entry, or on a copy, with fictional data; nothing here is real business.
>
> For each line: ✔ done as described, ✘ problem (write it in §5), — not applicable. Who: the role that performs it.

## 1. Before starting

| # | Check | Who | Result |
|---|---|---|---|
| 1.1 | Installation done by `deployment.md`, deployment record filled in | operator | |
| 1.2 | `https://<local name>` opens without a certificate warning on every counter and office PC, in Chrome or Edge (ADR-152, ADR-159) | operator | |
| 1.3 | Users created for each role (Administrator, Pharmacist, Seller, Accountant) with their own passwords | Administrator | |

## 2. Questions the accountant must answer (go-live blockers)

| # | Question | Answer | Who |
|---|---|---|---|
| 2.1 | **DR-040:** VAT is rounded per line on invoices and credit notes (a partial return may differ by at most 0.01 per line). Is that the legal rule? | | accountant |
| 2.2 | **DR-043:** are the printed mentions of invoices and credit notes complete (company NIF, NIS, RC, AI; customer's numbers when known; VAT by rate; amount in words)? Must cash invoices carry stamp duty (*droit de timbre*), and how much? | | accountant |
| 2.3 | **DR-041:** must some products be sold only on a veterinary prescription? If yes, which, and what must be recorded? | | pharmacy |

Another answer than the current behaviour means a new decision and a change **before** go-live (decisions.md). What each possible answer changes, and its size: `go-live-answers.md`.

## 3. Business checks

| # | Check | Who | Result |
|---|---|---|---|
| 3.1 | Create a product with packaging units and a barcode; find it by name, ingredient and scan | Pharmacist | |
| 3.2 | Purchase order → goods receipt with lot and expiry; the lot appears with its stock | Pharmacist | |
| 3.3 | Counter sale in cash with change, by FEFO; ticket printed on the **thermal printer** | Seller | |
| 3.4 | Credit sale on invoice to a customer within the limit; a sale beyond the limit refused | Seller | |
| 3.5 | An expired or blocked lot can never be sold | Seller | |
| 3.6 | Delivery note, then its invoice; **A4 invoice printed** and checked against §2 | Seller, accountant | |
| 3.7 | Customer return: restock and discard; the credit note printed | Pharmacist | |
| 3.8 | Lot recall: the buyers list, anonymous counter sales included; PDF | Pharmacist | |
| 3.9 | Customer payment on account allocated oldest first; statement printed; a reversal | Accountant | |
| 3.10 | Supplier invoice, credit note and payment; payables by due date | Accountant | |
| 3.11 | Cash session: open, movements, close with a difference; the session report | Seller, Accountant | |
| 3.12 | Physical count and regularisation; integrity page without mismatch | Pharmacist | |
| 3.13 | Reports: revenue, margin, valuation, statistics, receivables ageing, payables, cash differences, lot traceability; each in **CSV** (opens correctly in Excel) and **PDF** | Accountant | |
| 3.14 | A Seller cannot open margin, finance reports or administration | Administrator | |
| 3.15 | Audit log shows a price change, a cancellation and a stock correction with who and when | Administrator | |

## 4. Operations and accessibility

| # | Check | Who | Result |
|---|---|---|---|
| 4.1 | Backups page: **Run now** succeeds; download the file to the off-site disk | Administrator | |
| 4.2 | **Restore rehearsal** on the spare machine by `operations.md` §4, timed: under four business hours (ADR-150) | operator | time: |
| 4.3 | A release rehearsal (`operations.md` §2) on the spare machine | operator | |
| 4.4 | Printing ticket and A4 from **Chrome and from Edge** through the server (security headers in force, ADR-157) | Seller | |
| 4.5 | A whole sale with the keyboard only (J15 automates it; check on the real counter PC and scanner) | Seller | |
| 4.6 | The screens readable at 200 % zoom; the counter usable with a screen reader (NVDA or Narrator) for labels and error messages | pharmacy | |
| 4.7 | The application in Arabic: the counter and a report, right to left | pharmacy | |

## 5. Problems found

| # | Line | Description | Severity | Decision / fix | Retested |
|---|---|---|---|---|---|
| | | | | | |

## 6. Sign-off

The pharmacy accepts the application for go-live, with the answers of §2 applied and no open problem of severity High or Critical in §5.

| Role | Name | Date | Signature |
|---|---|---|---|
| Pharmacy (owner or manager) | | | |
| Accountant (for §2 and §3.6, 3.9, 3.13) | | | |
| Operator | | | |
