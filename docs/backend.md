# Backend Design — IMS Vétérinaire

> **Status:** Documentation / Architecture Phase — Implementation Not Started
> **Scope:** structure, responsibilities and rules. No Java code, entities, controllers, services or repositories are written in this phase.
> **Last updated:** 2026-10-18

Tags: `SRC` Spec · `SCH` Source Schema · `REC` recommendation · `OD-nnn` open decision · `ADR-nnn` decision.

## 1. Technology

| Concern | Choice |
|---|---|
| Language / build | Java 21 LTS, Maven wrapper (ADR-032) |
| Framework | Spring Boot 4.x (4.1.1 at Phase 0), Spring MVC (ADR-032) |
| Persistence | Spring Data JPA / Hibernate, MySQL 8.4 LTS (≥ 8.0.16 required for CHECK), Flyway (ADR-018) |
| Security | Spring Security, method security, BCrypt/Argon2 (`security.md`) |
| Mapping | MapStruct (ADR-019) |
| Validation | Jakarta Bean Validation + domain validation in services |
| Tests | JUnit 5, Mockito, Testcontainers MySQL (ADR-030), Spring Security Test |
| Observability | structured JSON logs, traceId, Actuator health only (ADR-031) |

## 2. Architecture style

**Modular monolith** (ADR-006): one deployable, packages per business module, explicit dependency rules (`architecture.md` §3). Layers inside every module: `api` (controllers + DTOs) → `application` (use-case services, transactions) → `domain` (entities, value objects, domain services, ports) → `infrastructure` (repositories, adapters).

```
com.imsvet.ims        (ADR-034)
  common/        errors, ids, money/quantity value objects, clock, paging, audit hooks
  auth/          login, tokens/sessions, password policy
  users/         users, roles, permissions
  catalog/       products, units, barcodes, categories, manufacturers, ingredients, species, dosage forms
  inventory/     lots, movements (ledger), adjustments, counts, reorder, expiry alerts
  purchasing/    suppliers, supplier prices, purchase orders, goods receipts, supplier returns, supplier invoices
  sales/         customers, quotes, delivery notes, invoices, POS, customer returns, credit notes
  finance/       customer & supplier payments, allocations, cash sessions, balances, statements
  reporting/     read-only reports (views / queries)
  audit/         audit log write + read
  numbering/     document number sequences
  settings/      application settings
  printing/      PDF generation
  backup/        backup trigger/log
```

(`lot` is merged into `inventory`, ADR-006.)

### 2.1 Layer responsibilities

| Layer | Does | Must not |
|---|---|---|
| Controller | map HTTP ↔ DTO, `@Valid`, delegate to **one** application service, return DTO | contain logic, touch repositories, expose entities |
| Application service | transaction boundary (`@Transactional`), permission check (`@PreAuthorize`), orchestration, audit call, idempotency | build SQL, return entities |
| Domain | invariants, state machines, FEFO selection, price resolution, conversion factors | know HTTP, JPA queries, security |
| Repository | queries/locking | business decisions |
| Mapper | entity ↔ DTO | logic, lazy loading outside transaction |

## 3. Module responsibilities and public ports

| Module | Owns (tables) | Exposes to others | Consumes |
|---|---|---|---|
| `catalog` | products, product_units, barcodes, categories, manufacturers, ingredients, species, dosage_forms, withdrawal periods | `ProductLookup`, `UnitConversion` | — |
| `inventory` | lots, stock_movements, inventory_counts(+lines), reorder tables | `StockService` (post movement, reserve/consume in a transaction), `LotQuery`, `FefoAllocator` | catalog |
| `purchasing` | suppliers, supplier_prices, purchase_orders(+lines), goods_receipts(+lines), supplier_returns, supplier_invoices | `SupplierLookup` | catalog, inventory, numbering |
| `sales` | customers, categories, customer_prices, sales documents(+lines), customer_returns, credit notes | `CustomerLookup` ; defines **`CreditLimitPort`** | catalog, inventory, numbering, finance (through port) |
| `finance` | payments, allocations, cash_sessions, cash_movements | implements `CreditLimitPort`, `BalanceQuery`, `PaymentRecorder` | sales/purchasing read ports |
| `reporting` | none (views/queries) | REST only | read access to others via views |
| `audit` | audit_log | `AuditRecorder` (must run inside the caller's transaction, BR-AUDIT-003) | — |
| `numbering` | document_sequences | `NumberingService.next(type)` | — |
| `settings` | app_settings | `SettingsProvider` (typed reads, e.g. login throttling limits) | audit |
| `users` | users, roles, permissions; password policy (ADR-039) | `UserAccounts` (credentials, active account, own password change) | audit |
| `auth` | sessions (ADR-036, Spring Session tables) | security chain, `SessionUser` principal | users, settings |

Ports invert dependencies so `sales` ↔ `finance` do not form a cycle; modules never read each other's tables directly except through views owned by `reporting`.

## 4. Entities (conceptual)

JPA entities mirror the tables in `database.md`; they are **internal** and never serialised. Conventions:
- identifiers: `BIGINT` generated; `@Version` on mutable aggregates (ADR-027);
- enums stored as strings (`@Enumerated(STRING)`) matching `VARCHAR`+CHECK columns (ADR-009);
- money/quantity as `BigDecimal` with explicit scale; never `double`;
- instants as UTC `Instant` (ADR-010);
- relationships default `LAZY`; aggregate boundaries respected (see `domain-model.md` §2);
- generated columns (`quantity_base`, line totals) are read-only for JPA (`insertable=false, updatable=false`) (ADR-016);
- append-only tables (`stock_movements`, `audit_log`, payments) expose no update path; entities immutable.

## 5. DTOs and mapping

- Request DTOs (`*Request`) and response DTOs (`*Summary`, `*Detail`) per use case; separate create/update DTOs; no entity in any signature of a controller.
- Server-owned fields are absent from request DTOs (or ignored).
- Decimals as strings in JSON (ADR-021), implemented via a single serializer/deserializer configuration.
- MapStruct mappers per module, explicit mappings for anything non-trivial; mappers never call repositories.

## 6. Validation strategy

| Level | Examples | Failure |
|---|---|---|
| Bean validation (DTO) | required, length, positive, pattern | `VALIDATION_FAILED` 400 |
| Application service | existence, active status, permission-dependent fields (price edit) | `NOT_FOUND`, `ACCESS_DENIED`, `*_INACTIVE` |
| Domain | state machines, quantity rules, FEFO, return limits | 409/422 business codes |
| Database | NOT NULL, FK, UNIQUE, CHECK, triggers (last line of defence) | mapped to business errors (CHECK error 3819 → `STOCK_INSUFFICIENT` or integrity error) |

Enforcement location of each business rule is in `business-rules.md` (column *Enforcement layer*). The database is never the only place where a user-facing message is produced.

## 7. Exception handling

- Domain/application exceptions carry a **code** from the catalogue (`api.md` §3) and optional context map.
- One `@RestControllerAdvice` converts: business exceptions → Problem Details; `MethodArgumentNotValid` → `VALIDATION_FAILED`; `ObjectOptimisticLockingFailureException` and lock timeouts (MySQL 1205/1213) → `CONCURRENT_MODIFICATION`; `DataIntegrityViolationException` → mapped via constraint-name table (unique/CHECK) else `INTERNAL_ERROR`; `AccessDenied` → `ACCESS_DENIED`; unknown → `INTERNAL_ERROR` (log with traceId, return neutral text).
- A **deadlock (1213)** on stock posting is retried a small bounded number of times by the application service (`[REC]` 2 retries) before failing as `CONCURRENT_MODIFICATION`.
- Constraint-name → code map is maintained in one place and covered by tests.

## 8. Transactions and concurrency

Detailed in `architecture.md` §5 and `workflows.md`. Rules summarised:
1. **One use case = one transaction** at the application service. Controllers and repositories are not transactional boundaries.
2. Isolation **READ COMMITTED** with explicit `SELECT … FOR UPDATE` (pessimistic) on lots in ascending id order (ADR-012/013) for any operation that moves stock.
3. Optimistic `@Version` for editable master/draft data.
4. Stock check and ledger insert happen in the same transaction as the lock; the lot balance is updated and verified (`CHECK >= 0`) in that transaction.
5. No remote calls or PDF rendering inside stock transactions; PDF generated after commit.
6. Auditing of a business action is written **in the same transaction** as the action (BR-AUDIT-003); a failed audit write fails the action.
7. Numbering: `NumberingService` increments the sequence row under lock in the **same** transaction; gaps are possible only on rollback and are governed by **OD-017**.

## 9. Security in the backend

Per `security.md`: session-based filter chain with CSRF (Spring Session JDBC, ADR-036), `@PreAuthorize("hasAuthority('perm.code')")` on **application service** methods (not only controllers), current user resolved from the security context only (never from request body), method-level checks for ownership (own cash session), no sensitive data in logs, CORS limited to configured origins, secrets from environment.

## 10. Auditing

`AuditRecorder` called by application services for the action codes listed in `security.md` §7; stores user, action, entity, entity id, old/new JSON (sensitive fields excluded), IP, timestamp, traceId. Audit rows are append-only; no endpoint modifies or deletes them.

## 11. Configuration

Externalised per profile (`dev`, `test`, `prod`): datasource, Flyway, security (token TTLs, CORS origins), business settings in the `app_settings` table (alert thresholds, default VAT, rounding, locale), file paths (backups, PDFs). No secrets in repository; `.env`/environment variables or secret store; `application-*.yml` hold non-secret defaults only. Hibernate `ddl-auto=validate` (never `update`/`create`).

## 12. Scheduled jobs

| Job | Purpose | Notes |
|---|---|---|
| Expiry refresh | compute/mark alert levels, expired lots become unsellable by rule not by job (BR-LOT-003 is evaluated at read/consume time) | idempotent |
| Reorder planner | generate proposals **[OPEN OD-013]** | idempotent per day |
| Backup | per OD-021 | logged in `backup_log` |
| Integrity check | reconcile balances vs ledger (BR-STOCK-007) | report only |
| Session cleanup | expired sessions (Spring Session JDBC cleanup job) | |

Jobs run with a system identity recorded in audit; in multi-instance deployments a lock (e.g. DB-based) prevents duplicates (single instance assumed `[REC]`).

## 13. Reporting

Read-only; implemented as native/JPQL queries or the rewritten MySQL views (ADR-014). Reports never write. Margin and revenue are net of credit notes (BR-RPT-001). Large exports stream.

## 14. Printing

`printing` renders PDFs from server-side templates (engine per OD-020), called after commit with the persisted document, so the output is reproducible from stored data.

## 15. Logging

JSON logs (Spring Boot structured logging, ECS format, all profiles; Phase 0) with `traceId` (MDC, set by `TraceIdFilter` from a well-formed incoming `X-Trace-Id` or generated), `userId` (id, not name), request path, outcome. No passwords, tokens, full PII payloads. Business events at INFO (document confirmed, recall), security events per `security.md` §12, errors at ERROR with stack trace only in logs.

## 16. Testing approach (summary; see `testing.md`)

Unit tests for domain (FEFO, conversion, price resolution, state machines); repository tests on Testcontainers MySQL for locks, CHECKs, triggers, generated columns; service integration tests for every workflow; API tests (MockMvc) for status codes, error bodies and permissions; concurrency tests for stock; migration tests (Flyway on empty and on seeded DB).

## 17. Coding conventions

Constructor injection; no field injection; no static state; small services per use case rather than god-services; names follow the ubiquitous language (`glossary.md`); no business logic in controllers, mappers, entities' setters or SQL scripts other than the documented triggers; every public use case has a test and references its BR IDs in Javadoc/test names.

## 18. Open backend items

OD-002/003 (sales stock/payment semantics), OD-006/007 (pricing/rounding), OD-011/012/013/015/017/020/021. (OD-024 resolved by ADR-032.)
