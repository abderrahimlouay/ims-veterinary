# CLAUDE.md — IMS Vétérinaire

> **Project phase: Phase 3 (Lots & units) complete (tag `phase-3-done`, 2026-10-22). Phase 4 decisions recorded (ADR-072…089, 2026-10-22); Phase 4 implementation waits for the user's approval of the plan.** Build/test commands are in `README.md`.

## 1. Project
Stock, purchasing, sales and finance application for a veterinary-medicine pharmacy/wholesaler (lots, expiry, FEFO, withdrawal periods, credit sales, recalls). Source of intent: the functional Spec (`said_project.md`). Everything you need is in `docs/`; start with `docs/roadmap.md` and `docs/development-workflow.md`.

## 2. Stack
React + Vite + TypeScript (Node 24) · Java 21 + Spring Boot 4.1 + Maven · MySQL 8.4 LTS (≥ 8.0.30) · Spring Data JPA/Hibernate · Flyway · REST/JSON under `/api` · Git · Linux. The legacy schema is PostgreSQL: it is **adapted, never blindly converted** (`docs/database-migration.md`).

## 3. Reading order before any work
1. `docs/roadmap.md` (current phase) → 2. `docs/decisions.md` (ODs of the phase) → 3. `docs/business-rules.md` → 4. `docs/workflows.md` → 5. `docs/api.md`, `docs/database.md` → 6. `docs/backend.md` / `docs/frontend.md` / `docs/security.md` / `docs/testing.md` as needed. Terms: `docs/glossary.md`.

## 4. Architecture rules
- Modular monolith; layers api → application → domain → infrastructure; module dependency rules in `docs/architecture.md` §3 (enforced by architecture tests).
- Controllers contain no logic and never expose entities (DTOs + MapStruct).
- Only the `inventory` module writes stock movements and lot balances (via `StockService`); only `finance` writes payments; only `numbering` issues document numbers.
- One use case = one `@Transactional` application-service method; lock lots `FOR UPDATE` in ascending id order; READ COMMITTED.
- Errors are RFC 9457 Problem Details with business `code` from `docs/api.md` §3.
- Decimals are JSON strings; `BigDecimal` everywhere; never `double`/`float` for money or quantity.

## 5. Business-critical rules (never violate; details in `docs/business-rules.md`)
1. Stock can never be negative (BR-STOCK-001) — checked in service **and** by DB CHECK.
2. Every stock change is an immutable, attributable movement (BR-STOCK-002/003/004).
3. A lot number and expiry date are mandatory on every stock entry (BR-LOT-001).
4. Expired, blocked or recalled lots can never be sold (BR-LOT-003/004); FEFO is proposed automatically (BR-LOT-005).
5. Confirmed documents are immutable; money records are never edited or deleted — reversed (BR-SALE-004, BR-PAY-003).
6. Totals, prices, lots and balances are computed by the backend; client values are ignored (BR-SALE-008).
7. Stock operations are atomic: all-or-nothing (BR-PURCH-007, BR-RET-005).
8. Document numbers are unique (BR-DOC-001).
9. Credit limits are enforced server-side (BR-PAY-005).
10. Sensitive actions are audited in the same transaction (BR-AUDIT-001/003).
11. Authorisation is permission-based and enforced in the backend; frontend hiding is cosmetic (BR-SEC-002).

## 6. Coding rules
Constructor injection; small use-case services; names follow `docs/glossary.md`; no business logic in controllers, mappers, SQL (other than documented triggers); no commented-out code; no TODO without a reference; no PostgreSQL-specific syntax; frontend components use design tokens only, never hard-coded colours (ADR-069, `docs/frontend.md` §6); `ddl-auto=validate`; schema changes **only** via small Flyway migrations (`docs/development-workflow.md` §6).

## 7. Testing rules
Every business-rule ID has a test (`docs/testing.md` §9); stock/locking/constraint tests run on Testcontainers MySQL (no H2); test failure paths and atomicity; concurrency tests for stock and numbering; fixed `Clock`; never disable or delete tests to get green.

## 8. Git rules
Short-lived branches `phase-<n>/<module>-<desc>`; Conventional Commits referencing rule/requirement IDs; small PRs; `main` always green; never force-push shared branches; never commit secrets, `.env`, dumps or real data.

## 9. Documentation rules
Update the affected docs in the same change; keep IDs stable; record every new ambiguity as an `OD-nnn` in `docs/decisions.md` and conflicts in `docs/documentation-review.md`.

## 10. OPEN DECISIONS
If a rule, workflow or API item is marked **OPEN DECISION / [OPEN OD-nnn]**, **stop and ask the user**. Present options and the `[REC]`; after the answer, convert it into an ADR and update the docs, then implement. Never guess, never silently default.

## 11. Forbidden behaviour
- Writing code for an undecided OD, or inventing requirements.
- Bypassing `StockService`, the permission checks, or the append-only rules.
- Relying on the frontend for security or business rules.
- Hard-coding secrets, thresholds, VAT, currency or company identity (use settings).
- Editing applied migrations; changing the schema outside Flyway.
- Exposing entities, stack traces or SQL in API responses.
- Starting the next roadmap phase without the user's approval.
- Treating the design-system prototype (`Vet stock design system.html`) as production code.
