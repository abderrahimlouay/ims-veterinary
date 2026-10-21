# IMS Vétérinaire

**Phase 0 (Foundation) implemented — awaiting review before Phase 1**

Inventory, purchasing, sales and finance management for a veterinary-medicine business: lot and expiry tracking (FEFO), withdrawal-period warnings, lot recall, credit sales and customer accounts, cash sessions, supplier management and reporting.

## Status
Phase 0 delivers an empty but buildable, tested skeleton: Spring Boot backend with module packages, architecture tests, Flyway baseline, Problem Details error handling, trace id, JSON logs and Actuator health; React frontend shell with French/Arabic (RTL)/English, API client and design tokens; GitHub Actions CI. No business features exist yet. See `docs/roadmap.md`.

## Stack
Java 21 · Spring Boot 4.1 · Maven · MySQL 8.4 LTS · Flyway · React 19 + Vite + TypeScript · Tailwind CSS 4 · Node.js 24 · GitHub Actions (ADR-032).

## Prerequisites
- JDK 21, Node.js 24 with npm.
- Docker, usable **without sudo** by your user (Testcontainers starts MySQL for the backend tests). On Linux: `sudo usermod -aG docker $USER`, then log out and in.
- For running the backend outside tests: a MySQL 8.4 database started with `log_bin_trust_function_creators=1` (ADR-043; Flyway creates triggers) and the variables of `.env.example`.

## First start (ADR-040)
On an empty database, set `IMS_BOOTSTRAP_ADMIN_USERNAME` and `IMS_BOOTSTRAP_ADMIN_PASSWORD` (≥ 12 characters, not a common password) once: the application creates that Administrator, who must change the password at first login. Remove both variables afterwards; they are ignored as soon as a user exists.

## Commands

### Backend (`backend/`)
| Purpose | Command |
|---|---|
| Full build with all tests (unit, architecture, API, migration on MySQL container) | `./mvnw verify` |
| Tests without Docker (architecture, unit, MockMvc) | `./mvnw test -Dtest='ArchitectureTest,ErrorCatalogueTest,BusinessCalendarTest,DecimalJsonTest,ProblemDetailsHandlerTest'` |
| Run locally against a throw-away MySQL container | `./mvnw spring-boot:test-run` |
| Run locally against your own MySQL (profile `dev`) | `IMS_DB_PASSWORD=… ./mvnw spring-boot:run` |
| Health check | `curl localhost:8080/actuator/health` |

### Frontend (`frontend/`)
| Purpose | Command |
|---|---|
| Install | `npm ci` |
| Dev server (proxies `/api` to `localhost:8080`) | `npm run dev` |
| Lint · typecheck · unit tests | `npm run lint` · `npm run typecheck` · `npm test` |
| Production build | `npm run build` |
| End-to-end smoke (first time: `npx playwright install chromium`) | `npm run test:e2e` |

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
