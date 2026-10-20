# IMS Vétérinaire

**Documentation / Architecture Phase — Implementation Not Started**

Inventory, purchasing, sales and finance management for a veterinary-medicine business: lot and expiry tracking (FEFO), withdrawal-period warnings, lot recall, credit sales and customer accounts, cash sessions, supplier management and reporting.

## Status
This repository currently contains **documentation only**. No application code, entities, controllers, migrations or Docker files exist yet. The next step is for the user to review `docs/documentation-review.md`, answer the open decisions that block Phase 0, and then instruct Claude Code to implement Phase 0.

## Stack (target)
React + Vite + TypeScript · Java + Spring Boot + Maven · MySQL 8.4 · REST/JSON · Git · Linux.

## Layout
```
ims-veterinary/
  CLAUDE.md        instructions for Claude Code (read first)
  README.md
  docs/            requirements, architecture, database, migration, domain model,
                   business rules, workflows, security, api, frontend, backend,
                   testing, roadmap, development workflow, decisions, glossary,
                   documentation review
  backend/         (empty — Spring Boot project to be created in Phase 0)
  frontend/        (empty — React/Vite project to be created in Phase 0)
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

## Starting implementation
Open the project in Claude Code and say: *"Read CLAUDE.md and the docs. We are now ready to implement Phase 0."* Phases that depend on open decisions will pause and ask.

## Security note
Never commit secrets or real data. The backend is the authoritative security boundary.
