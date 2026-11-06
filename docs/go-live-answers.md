# Go-live answers — what each answer changes (DR-040, DR-041, DR-043)

> **Purpose:** the accountant (questions 1–4) and the pharmacy (question 5) answer the questions sent on 2026-11-06 (`uat.md` §2). This page says, for each possible answer, what changes in the application, which tests prove it and how large the work is, so the change can start the day the answer arrives.
> **Status:** a plan, not a decision. Nothing here is built. Each answer that differs from today's behaviour becomes an ADR first (CLAUDE.md §10), then a small PR. An answer "Oui, c'est conforme" closes the item with no code change.
> **Rule for every change:** it lands **before** the first real invoice, credit note or sale (go-live starts from an empty database, ADR-160), so no issued document is ever recomputed or reprinted differently (BR-SALE-004).

## Summary

| Question | Item | If "conforme" | If not: likely change | Size |
|---|---|---|---|---|
| 1 VAT per line | DR-040 | close, ADR note | VAT per rate on the document | M |
| 2 Partial credit notes | DR-040 | close | follows question 1; or a credit note by recomputation | S–M |
| 3.1 Mentions | DR-043 | close | add the missing mentions to the PDFs | S per mention |
| 3.2 Customer numbers | DR-043 | close | numbers required for professional customers on an invoice | S |
| 4 Stamp duty | DR-043 | close (no stamp) | stamp-duty computation, line, settings | L |
| 5 Prescription | DR-041 | close | product flag, prescription capture, register | M–L |

Sizes: S under a day, M one to three days, L three to five days, each with its tests and documentation.

## Question 1 — VAT per line or per rate (DR-040)

**Today (ADR-093, ADR-115):** `SaleAmounts.lineVat` rounds the VAT of each line HALF_UP to 0.01; the generated columns `line_ht`, `line_vat`, `line_ttc` of `sales_document_lines` (V07_002, V08_002) use the same formula (ADR-016 parity); `SaleTotals` sums the rounded lines; the PDF shows the VAT by rate as the sum of its lines.

**A. Conforme.** Close DR-040 (with question 2), note the answer and its reference in ADR-115. No code.

**B. VAT per rate on the total.** The VAT of a document is, for each rate, `ROUND(sum of line_ht at that rate × rate, 2)`; totals become the sum of those per-rate amounts.
- Backend: `SaleTotals` computes per-rate VAT; `total_vat` and `total_ttc` of `sales_documents` stored from it; the per-line VAT stays on the line for information only, or is removed from the PDF (to ask the accountant). The generated `line_vat` column remains (it is the line's own figure) but no longer sums to the total: a new Flyway migration adds `sales_document_vat (document_id, vat_rate, base_ht, vat)` so the per-rate amounts are stored, not recomputed (BR-SALE-004).
- Customer balances, receivables (`v_customer_receivables`), revenue and margin views read `total_ttc` / `total_ht`: unchanged in shape; VAT reports read the new table.
- PDF: the VAT table prints the stored per-rate rows.
- Tests: `SaleAmounts`/`SaleTotals` unit tests with the 2 × 0.33 DA example (0.13, not 0.12); an integration test that stored totals equal the per-rate sum; report parity tests rerun; PDF test of the VAT table.
- Docs: new ADR (supersedes the VAT part of ADR-115), BR-SALE-008, `database.md`, `api.md` (document totals and per-rate VAT in the response).

## Question 2 — Credit notes and partial returns (DR-040)

**Today (ADR-130):** `ReturnCredit` credits the pro-rata of a line, HALF_UP to 0.01, never more than what is left; the return that completes a line credits the exact remainder.

**A. Conforme.** Close with question 1.

**B. Question 1 answered "per rate".** A credit note follows the same rule: per-rate VAT of the credited lines, with the last credit note of an invoice crediting the exact per-rate remainder so the total credited never exceeds what was invoiced. `ReturnCredit` gains the per-rate remainder; same new table for the credit note. Size M, done with question 1.

**C. Each credit note recomputed directly (quantity × price), no remainder rule.** The last return no longer absorbs the cent: a fully returned line may then be credited 0.01 more or less than invoiced. The remaining-to-credit check (BR-RET-001) must then allow that cent, and the customer balance may keep a 0.01 residue; the accountant must accept that. Size S–M. Tests: the 3 × 33.33 DA example of the questions (6.33, 6.33, 6.33), balance after a full return.

## Question 3.1 — Printed mentions (DR-043)

**Today (ADR-140, ADR-141, ADR-142):** see the table of question 3 in the questions document; printed by `SalePdfs`, amount in words by `AmountInWords`.

**A. Complete.** Close the mentions part of DR-043.

**B. A mention is missing.** Each one is a small change, depending on where its value comes from:
- A fixed text (for example a legal sentence or payment conditions): a company setting (ADR-140, editable by an administrator), printed by `SalePdfs`. Size S.
- A company number not yet held (for example a capital amount): a new company-profile field (migration, settings API, form) and the PDF. Size S.
- A value per document (for example the place of issue, the delivery date): a column on `sales_documents` captured at confirmation. Size S–M.
- Tests: the PDF text test asserts the new mention on the invoice and the credit note, in French.

## Question 3.2 — Customer legal numbers required (DR-043)

**Today (ADR-141):** NIF, NIS, RC and AI are optional on every customer.

**A. Optional is fine.** Close.

**B. Required for a professional customer's invoice.** The rule lives in the backend: confirming an **invoice** (not a receipt, quote or delivery note) for a customer of a professional type (today's types are `breeder`, `veterinarian`, `other`: the accountant says which ones; a `reseller` type would be a new value) fails with a new business code `CUSTOMER_LEGAL_NUMBERS_MISSING` (Problem Details, `api.md` §3) naming the missing numbers; the customer form marks them required for those types, as a hint only. Existing demo customers are not real data, so no backfill. Size S. Tests: confirmation refused and accepted, the code in `api.md` §3 (docs PRs can fail backend CI otherwise), frontend message.

## Question 4 — Stamp duty on cash payments (DR-043)

**Today (ADR-141 item 3):** no stamp duty anywhere.

**A. Not applicable.** Close; ADR note with the accountant's reference.

**B. Applicable.** The accountant's answers to 4.2–4.6 fix the parameters; none of them is guessed (CLAUDE.md §11: rates and thresholds are settings, never constants).
- Settings: `stamp_duty_rate`, `stamp_duty_minimum`, `stamp_duty_maximum`, `stamp_duty_threshold`, and whether it applies to receipts (answer 4.3). Seeded empty, so it stays off until the administrator fills them in.
- Backend: a pure `StampDuty.of(cashPart, settings)` in `sales/domain` with the accountant's rounding; computed at confirmation on the cash part only (answer 4.4: the cash tender of a mixed payment, or the whole document); stored on the document (new columns `stamp_duty`, `net_to_pay` by migration) so it never changes after issue (BR-SALE-004). The cash session expects the cash including the stamp (BR-CASH-003); the customer balance and receivables use `net_to_pay` for a credit invoice paid later in cash only if the accountant says so (a credit invoice is normally not paid in cash at issue).
- Credit note (answer 4.6): refund the stamp proportionally, entirely on a full return, or never: one more column on the credit note.
- PDF (and the ticket if 4.3 says so): a "Droit de timbre" line after the TTC and a "Net à payer" (answer 4.5); amount in words on the net.
- Reports: revenue and margin stay HT (unchanged); a stamp-duty total per period in the revenue report if the accountant wants it for the declaration.
- Tests: `StampDuty` unit tests on the scale (below threshold, minimum, maximum, rounding); confirmation stores it; mixed tender; cash session expected amount; credit-note refund; PDF line; upgrade migration test.
- Size L.

## Question 5 — Prescription-only products (DR-041, pharmacy)

**Today (ADR-124):** no prescription attribute; every sellable lot can be sold.

**A. No prescription rule.** Close DR-041.

**B. Some products need a prescription.** The pharmacy's answers to 5.1–5.4 fix the shape.
- Catalogue: `products.prescription_required` (migration, default false), on the product form with `product.update`, audited like a price change; or on the therapeutic category if the answer is "a whole category" (5.1).
- Sale: a sale line of such a product needs a prescription record on the document: `sale_prescriptions (document_id, number, prescribed_on, veterinarian_id or name, breeder, species)`, the fields the pharmacy lists (5.2), captured on the POS and the invoice form.
- Enforcement (5.3): **refuse** — confirmation fails with a new code `PRESCRIPTION_REQUIRED` (backend check, BR-SEC-002 style; frontend only shows the form); or **warn** — confirmation succeeds and the missing prescription is audited and listed.
- Register (5.4): a report "Ventes sur ordonnance" by period, with CSV and PDF like the other reports (ADR-149), behind a report permission.
- Tests: refusal or warning on confirmation, prescription stored with the document, register report and its export, product flag audited, e2e on the POS.
- Size M (flag and warning) to L (capture, refusal and register).

## After the answers

1. Write the answers into the questions document's table and `uat.md` §2.
2. One ADR per answer that changes behaviour; DR-040, DR-041, DR-043 resolved in `documentation-review.md`.
3. Small PRs in this order: settings and schema first, then computation, then PDFs and screens, then reports.
4. Regenerate the sample invoice and credit note and send them back to the accountant for a last check before the UAT (§3.6).
