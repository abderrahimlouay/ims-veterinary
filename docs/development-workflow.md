# Development Workflow — IMS Vétérinaire

> **Status:** Documentation / Architecture Phase — Implementation Not Started
> **Audience:** the user and Claude Code (the future implementer).
> **Last updated:** 2026-10-18

## 1. Roles

| Actor | Responsibility |
|---|---|
| User (product owner) | decides OPEN DECISIONs, approves phases, reviews merges |
| Claude Code (implementer) | implements one slice at a time from the docs, tests, commits, updates docs |
| Documentation (this folder) | the source of truth; code that disagrees with it is a defect in one of them |

Order of authority when documents conflict: **`said_project.md` Spec** (intent) → `decisions.md` (accepted ADRs) → `business-rules.md` → `api.md` / `database.md` → other docs. Conflicts are recorded in `documentation-review.md`, not silently resolved.

## 2. The Claude Code loop (per slice)

1. **Read** `CLAUDE.md`, the roadmap phase, and the documents it names (requirements, business rules, workflows, api, database for the slice).
2. **Check decisions:** list every OD referenced by the slice. If any is not Accepted → **stop and ask the user**; do not pick a default.
3. **Plan** the slice in a few lines (files, migration, tests, endpoints); keep it small (one use case or one entity group).
4. **Test first or alongside:** write the failing tests derived from the BR IDs of the slice (`testing.md` §9).
5. **Migrate:** add one small Flyway migration (§6).
6. **Implement** backend (domain → application → API) then frontend, respecting module rules.
7. **Run** the full relevant test set locally (unit + integration + lint); fix until green.
8. **Review own diff** against the checklist (§5).
9. **Update docs** touched (api/database/decisions/glossary/review) in the same change.
10. **Commit** (§4), then report: what was done, tests run, open items.
11. **Stop at the end of the phase** and wait for the user's approval before starting the next.

## 3. Handling OPEN DECISIONs

- An `OPEN DECISION` in a doc means the behaviour is **not specified**. Claude Code must not infer it from the schema or from habit.
- When blocked: present the question, the options with consequences (from `decisions.md`), the recommendation `[REC]`, and ask the user.
- When the user decides: change the OD to an ADR (Accepted) in `decisions.md`, update every document that referenced it (search the OD ID), then implement.
- If the user says "use your recommendation", record it as an ADR stating it is the user-approved recommendation.
- Never leave a code TODO as a substitute for a decision.

## 4. Git workflow

- Trunk-based with short-lived branches: `main` always green and releasable.
- Branch naming: `phase-<n>/<module>-<short-description>` (e.g. `phase-5/purchasing-goods-receipt`); fixes `fix/<short>`; docs `docs/<short>`.
- One logical change per commit; **no commit mixes migration, refactor and feature unrelated work**.
- Commit message: Conventional Commits — `feat(inventory): post movement on receipt (BR-STOCK-002)`; types `feat|fix|docs|test|refactor|chore|build|ci`; body explains *why*; reference BR/FR/ADR/OD IDs.
- Pull requests: small (< ~400 changed lines excluding generated), description lists the requirements and rules covered, tests added, docs updated, migrations included.
- Never force-push `main`; never rewrite shared history; never commit secrets, `.env`, dumps with real data, or build output.
- Tags per completed phase: `phase-<n>-done`.
- Merge only with green CI and the Definition of Done (`testing.md` §11).

## 5. Self-review checklist (before every commit)

- [ ] Every business rule touched has a test; failure paths covered.
- [ ] No business logic in controllers, mappers or entities' setters.
- [ ] Permission check on the application service method; endpoint listed in the permission matrix.
- [ ] Stock changes only through `StockService`; locks in ascending lot id; one transaction.
- [ ] No `double`/`float` for money or quantity; decimals as strings in JSON.
- [ ] No PostgreSQL-specific syntax; migration is MySQL 8.4 and small.
- [ ] Errors use codes from `api.md` §3 (add new codes to the doc).
- [ ] No secrets, no sensitive data in logs.
- [ ] Naming follows `glossary.md`.
- [ ] Docs updated in the same change.

## 6. Database migration rules (Flyway)

1. Forward-only; never edit an applied migration.
2. File naming `V<phase>_<seq>__<description>.sql` (e.g. `V05_003__create_goods_receipts.sql`).
3. **One concern per migration** (MySQL DDL is non-transactional, ADR-018): a failed script may leave partial changes, so keep each script atomic in meaning and idempotent where possible.
4. Each migration is tested on an empty DB and on the previous state (`testing.md` §12).
5. Destructive changes use expand → migrate → contract across releases.
6. Schema deltas must reference SD-IDs from `database-migration.md`; new deltas are added there first.
7. `spring.jpa.hibernate.ddl-auto=validate` always; entities never generate schema.
8. Seed/reference data in separate versioned migrations (`R__` repeatable only for views).
9. Production credentials never appear in migrations.

## 7. Environments and configuration

- Local: MySQL via Testcontainers or a local instance; `application-dev.yml` non-secret defaults.
- CI: Testcontainers, no network secrets.
- Secrets: environment variables / secret store only; `.env` files are git-ignored; provide `.env.example` without values.

## 8. Definition of Ready (for starting a phase)

Prerequisite phases done; ODs of the phase Accepted; relevant docs reviewed with no open Critical/High items for the phase in `documentation-review.md`; test data plan available.

## 9. Documentation maintenance

- Docs are versioned with code; a PR that changes behaviour without changing the doc is rejected.
- Changing a business rule: update `business-rules.md`, `testing.md` matrix row, `workflows.md`/`api.md` if affected, and add an ADR.
- Re-run the consistency audit (`documentation-review.md` §3 describes it) at the end of every phase.
- Keep IDs stable; never reuse a retired ID.

## 10. Forbidden behaviour for the implementer

Writing code for an undecided OD; changing schema outside Flyway; bypassing `StockService`; adding a "temporary" permission bypass; using H2 for stock tests; disabling or deleting tests to go green; committing generated secrets; mass refactors not requested; starting the next phase without approval; inventing requirements — propose them as a change request to the user instead.

## 11. Reporting format after each slice

1. What was implemented (requirement/rule IDs). 2. Tests added/run and results. 3. Docs changed. 4. Deviations or new open questions. 5. Next proposed slice.
