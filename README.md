# IMS Vétérinaire

**Phase 3 (Lots & units) complete — Phase 4 (Inventory & stock) decided, not started**

Inventory, purchasing, sales and finance management for a veterinary-medicine business: lot and expiry tracking (FEFO), withdrawal-period warnings, lot recall, credit sales and customer accounts, cash sessions, supplier management and reporting.

## Status
Phase 0 delivered the tested skeleton (module packages, architecture tests, Flyway, Problem Details, trace id, JSON logs, health, React shell in French/Arabic (RTL)/English, CI). Phase 1 adds security and administration: session login with CSRF, throttling and password policy, first-administrator bootstrap, users, roles and permissions, application settings and the audit log, with their administration pages and end-to-end journeys J1 and J11. Phase 2 adds the catalogue: reference lists (seeded values translated in fr/ar/en), products with prices per base unit (audited changes), thresholds, active ingredients and substitutes, target species and withdrawal periods, and a product search answering in well under 1 s on 50,000 products. Phase 3 adds packaging units with exact conversion, barcodes per unit with scanning, and lots: computed status and expiry levels from the settings, FEFO preview, expiry alerts, block / unblock / recall with audit, and the lot screens and dashboard widget. Phase 4 adds the stock: an append-only ledger written only by the stock service (two database accounts, triggers), stock levels and in-stock search, manual adjustments, opening stock, physical inventory counts with regularisation, reorder proposals and a nightly ledger integrity check, with their screens and journey J8. Purchasing, sales and finance come in the next phases. See `docs/roadmap.md`.

## Stack
Java 21 · Spring Boot 4.1 · Maven · MySQL 8.4 LTS · Flyway · React 19 + Vite + TypeScript · Tailwind CSS 4 · Node.js 24 · GitHub Actions (ADR-032).

## Prerequisites
- JDK 21, Node.js 24 with npm.
- Docker, usable **without sudo** by your user (Testcontainers starts MySQL for the backend tests). On Linux: `sudo usermod -aG docker $USER`, then log out and in.
- For running the backend outside tests: a MySQL 8.4 LTS database (8.0.30 at least; an older server is refused at migration time) started with `log_bin_trust_function_creators=1` (ADR-043; Flyway creates triggers), its two accounts (below) and the variables of `.env.example`.

## Database accounts (ADR-087)
The application never connects with the account that changes the schema:

| Account | Variables | Rights |
|---|---|---|
| Migration (Flyway only) | `IMS_DB_MIGRATION_USERNAME`, `IMS_DB_MIGRATION_PASSWORD` | every right on the application schema, with `GRANT OPTION` |
| Application | `IMS_DB_USERNAME`, `IMS_DB_PASSWORD` | none at creation; after each migration Flyway grants `SELECT, INSERT, UPDATE, DELETE` on every table and only `SELECT, INSERT` on `stock_movements` and `audit_log` — no DDL |

Create them once as a MySQL administrator, with your own strong passwords:

```sql
CREATE USER '<migration-user>'@'%' IDENTIFIED BY '<migration-password>';
GRANT ALL PRIVILEGES ON `<schema>`.* TO '<migration-user>'@'%' WITH GRANT OPTION;
CREATE USER '<application-user>'@'%' IDENTIFIED BY '<application-password>';
```

If a variable is missing, or the same account is given for both roles, the application stops at start with a report that says exactly this. Passwords come **only from environment variables**; nothing in the repository holds one. The `dev` profile defaults the URL (`localhost:3306/ims`) and the account names (`ims_app`, `ims_migrator`) for a local database only: these names are public and must not be used in production, where the `prod` profile has no default at all and every variable must be set. Tests, `spring-boot:test-run` and CI create both accounts in their throw-away container with random passwords.

## First start (ADR-040)
On an empty database, set `IMS_BOOTSTRAP_ADMIN_USERNAME` and `IMS_BOOTSTRAP_ADMIN_PASSWORD` (≥ 12 characters, not a common password) once: the application creates that Administrator, who must change the password at first login. Remove both variables afterwards; they are ignored as soon as a user exists.

## Bulk loads (ADR-091)
After any bulk load (legacy import, a large opening-stock load, generated data), refresh the statistics of the loaded tables with the migration account before users work, for example `ANALYZE TABLE products, product_active_ingredients, lots, stock_movements;`. Until InnoDB refreshes them on its own, MySQL may plan searches as if the tables were empty and scan them.

## Commands

### Backend (`backend/`)
| Purpose | Command |
|---|---|
| Full build with all tests (unit, architecture, API, migration on MySQL container) | `./mvnw verify` |
| Tests without Docker (architecture, unit, MockMvc) | `./mvnw test -Dtest='ArchitectureTest,ErrorCatalogueTest,BusinessCalendarTest,DecimalJsonTest,ProblemDetailsHandlerTest'` |
| Run locally against a throw-away MySQL container | `./mvnw spring-boot:test-run` |
| Trial with demo data (fictional; reset = restart: a new container is migrated and seeded; never in prod) | `IMS_BOOTSTRAP_ADMIN_USERNAME=admin IMS_BOOTSTRAP_ADMIN_PASSWORD=… ./mvnw spring-boot:test-run -Dspring-boot.run.profiles=dev,demo` — the other demo users' passwords are printed once at start, or set `IMS_DEMO_PASSWORD`. Each start uses a new throwaway database: data entered while testing is lost when the app stops. Includes 12 fictional customers (3 categories, own prices, limits "cash only", "no limit" and amounts, one inactive); their phone numbers are in the reserved fake range `0100 00 xx xx` |
| Run locally against your own MySQL (profile `dev`) | `IMS_DB_PASSWORD=… IMS_DB_MIGRATION_PASSWORD=… ./mvnw spring-boot:run` (two accounts from Phase 4, ADR-087: Flyway uses the migration account, the application the restricted one) |
| Health check | `curl localhost:8080/actuator/health` |

### Frontend (`frontend/`)
| Purpose | Command |
|---|---|
| Install | `npm ci` |
| Dev server (proxies `/api` to `localhost:8080`) | `npm run dev` |
| Lint · typecheck · unit tests | `npm run lint` · `npm run typecheck` · `npm test` |
| Production build | `npm run build` |
| End-to-end journeys against the real backend (needs Docker and JDK 21; starts the backend on a MySQL container itself; first time: `npx playwright install chromium`) | `npm run test:e2e` |

CI (`.github/workflows/ci.yml`) runs all of the above plus a secret scan and `npm audit` on every push to `main` and every pull request.

## Layout
```
ims-veterinary/
  CLAUDE.md        instructions for Claude Code (read first)
  README.md
  .env.example     environment variables (copy to .env, never commit it)
  .github/         CI workflow
  docs/            requirements, architecture, database, migration, domain model,
                   business rules, workflows, security, api, frontend, backend,
                   testing, roadmap, development workflow, decisions, glossary,
                   documentation review
  backend/         Spring Boot application (com.imsvet.ims, modules per ADR-034)
  frontend/        React/Vite SPA
```

## Documentation map
| Document | Content |
|---|---|
| `docs/requirements.md` | functional and non-functional requirements by module |
| `docs/architecture.md` | system, modules, flows, transactions, deployment (conceptual) |
| `docs/database.md` | MySQL data model, constraints, queries, transactions |
| `docs/database-migration.md` | PostgreSQL → MySQL analysis and schema delta register |
| `docs/domain-model.md` | aggregates, state machines, invariants |
| `docs/business-rules.md` | 110 rules with IDs, enforcement layers and error conditions |
| `docs/workflows.md` | purchasing, sales, returns, counts, recall, payments, cash… |
| `docs/security.md` | authentication, RBAC, permission catalogue, audit |
| `docs/api.md` | REST conventions, error catalogue, endpoint contracts |
| `docs/frontend.md` | SPA structure, pages, components, states |
| `docs/backend.md` | modules, layers, conventions |
| `docs/testing.md` | test strategy and rule-to-test matrix |
| `docs/roadmap.md` | Phases 0–12 and requirement-to-phase matrix |
| `docs/development-workflow.md` | Claude Code loop, Git, migrations |
| `docs/decisions.md` | ADRs and OPEN DECISIONS |
| `docs/glossary.md` | ubiquitous language (EN/FR) |
| `docs/documentation-review.md` | consistency audit and unresolved issues |

## Reading order for a new contributor
`README.md` → `CLAUDE.md` → `docs/requirements.md` → `docs/decisions.md` → `docs/architecture.md` → `docs/roadmap.md` → the documents of the phase being implemented.

## Security note
Never commit secrets or real data. The backend is the authoritative security boundary.
