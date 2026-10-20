# Security Architecture — IMS Vétérinaire

> **Status:** Documentation / Architecture Phase — Implementation Not Started
> **Last updated:** 2026-10-20

**Governing principle (ADR-029):** the backend is the authoritative security boundary. The frontend hides what a user cannot do, but every rule is re-checked on the server. Nothing in this document relies on the client behaving correctly.

Tags: `SRC` Spec · `SCH` Source Schema · `REC` recommendation · `OD-nnn` open decision.

## 1. Security requirements traceability

| Requirement | Basis | Where addressed |
|---|---|---|
| Secure authentication by username/password | SRC FR-SEC-001 | §2 |
| Roles with restricted rights (Administrator, Pharmacist, Seller, Accountant) | SRC FR-SEC-002 | §3, §4 |
| Audit of sensitive actions (price changes, invoice cancellations, stock corrections) | SRC FR-AUD-001 | §7 |
| Export and automatic backup | SRC FR-AUD-002 | §10 |
| Password hash, never plaintext | SCH | §2.2 |
| Immutable stock history | SRC FR-STOCK-002 | ADR-008, §9 |
| Hardening beyond the Spec (headers, rate limits, secrets, SQL-injection, logging) | REC | §5–§12 |

## 2. Authentication

### 2.1 Flow (mechanism-independent)
1. `POST /api/auth/login` with username + password over HTTPS.
2. Server loads the user, checks `is_active` (BR-SEC-004), verifies the password hash, applies throttling (BR-SEC-005).
3. On success the server issues a credential (§2.3), updates `last_login_at`, writes a security-event log line.
4. Every later request carries the credential; a security filter authenticates it **before** any controller runs (default-deny, BR-SEC-001).
5. `POST /api/auth/logout` invalidates the credential (server-side where the mechanism allows).
6. Error messages never reveal whether the username or the password was wrong (`AUTH_INVALID_CREDENTIALS`).

### 2.2 Password storage and policy
| Item | Decision |
|---|---|
| Hash | **bcrypt, cost 12** (ADR-039) via Spring Security `DelegatingPasswordEncoder`, so the algorithm can be upgraded and hashes migrated at next login |
| Storage | `users.password_hash` only; never logged, never returned by any API, never in audit `old_values/new_values` |
| Policy (ADR-039) | minimum 12 characters, no composition rules, rejected if in the bundled common-password list, no forced periodic rotation; change requires current password; Administrator reset generates a one-time temporary password; `users.must_change_password` then limits the user to `me`, `change-password` and `logout` (`PASSWORD_CHANGE_REQUIRED` otherwise) |
| First administrator (ADR-040) | created at startup only when `users` is empty, from `IMS_BOOTSTRAP_ADMIN_USERNAME` / `IMS_BOOTSTRAP_ADMIN_PASSWORD`, with `must_change_password = true` — **never** hard-coded in a migration or repository |
| Disabled users | `is_active = false` blocks login; the user's sessions are deleted and any request is refused at once |

### 2.3 Credential mechanism — server-side session (ADR-036, resolves OD-001)

| Item | Decision |
|---|---|
| Credential | opaque session id in a `HttpOnly; Secure; SameSite=Strict` cookie; session state in MySQL (Spring Session JDBC, tables created by Flyway) |
| Session content | user id only; active flag, role and permissions re-read on every request (deactivation and permission changes apply on the next request) |
| Lifetime | idle timeout 30 min, absolute 10 h (configuration properties); new session id at login (fixation protection) |
| Revocation | logout deletes the session; deactivating a user deletes all their sessions |
| CSRF | mandatory on every state-changing request, login included: token in a readable `XSRF-TOKEN` cookie, sent back in the `X-XSRF-TOKEN` header |
| Not used | no JWT, no refresh endpoint, no signing key |

**No credential in `localStorage`/`sessionStorage`; no credential in URLs; no credential in logs.**

## 3. Authorization model

- **RBAC with fine-grained permissions** (`roles`, `permissions`, `role_permissions`; one role per user, `users.role_id`).
- Enforcement points, outermost first:
  1. **Security filter chain:** authenticated vs anonymous; `/api/auth/login` (and optional health) are the only public routes.
  2. **Method security** on application-service methods: `@PreAuthorize("hasAuthority('sale.cancel')")` style, one permission per use case. Controllers do not decide permissions on their own.
  3. **Object-level checks** where a permission is not enough, e.g. a cashier may close **only their own** cash session unless they hold `cash.manage`.
  4. **State checks** (document status, lot status) are business rules, not authorization (`business-rules.md`).
- Authorities are re-read from the database on every request (ADR-036); permission changes take effect on the next request.
- **Default deny:** a controller method without an explicit permission annotation fails an architecture test (ArchUnit) in CI `[REC]`.
- Authorization failures return `ACCESS_DENIED` (403); unauthenticated requests `AUTH_REQUIRED` (401). Existence of a forbidden resource is not leaked beyond what 403/404 conventions imply.

## 4. Permission catalogue and role matrix (accepted — ADR-037)

The Spec names four roles with "restricted rights" but gives no matrix. The catalogue below was proposed (`REC`) and **accepted as written by the project owner on 2026-10-20** (ADR-037); it is seeded by migration in Phase 1. `●` = granted, `–` = not granted. A = Administrator, P = Pharmacist, S = Seller, C = Accountant (Comptable).

| Permission code | Meaning | A | P | S | C |
|---|---|---|---|---|---|
| `user.read` | list/view users | ● | – | – | – |
| `user.manage` | create/edit/deactivate users, reset passwords | ● | – | – | – |
| `role.manage` | edit role–permission mapping | ● | – | – | – |
| `settings.manage` | edit settings (alert thresholds, VAT default, company data) | ● | – | – | – |
| `audit.read` | read audit log | ● | – | – | ● |
| `backup.manage` | run/download backups, view backup log | ● | – | – | – |
| `product.read` | view catalogue, search | ● | ● | ● | ● |
| `product.create` / `product.update` | create / edit products, units, barcodes, withdrawal periods | ● | ● | – | – |
| `product.archive` | archive / restore products | ● | ● | – | – |
| `price.edit` | change sale/purchase/unit prices; override a resolved price at sale | ● | ● | – | – |
| `reference.manage` | manage manufacturers, categories, forms, ingredients, species | ● | ● | – | – |
| `lot.read` | view lots, FEFO, expiry alerts | ● | ● | ● | ● |
| `lot.block` / `lot.unblock` | block / unblock a lot | ● | ● | – | – |
| `lot.recall` | recall a lot and obtain buyers list | ● | ● | – | – |
| `stock.read` | view stock levels and movements | ● | ● | ● | ● |
| `stock.adjust` | manual adjustments | ● | ● | – | – |
| `inventory.count` | create/enter inventory counts | ● | ● | – | – |
| `inventory.validate` | regularise counts | ● | ● | – | – |
| `reorder.read` / `reorder.manage` | view / convert / dismiss reorder proposals | ● | ● | – | – |
| `supplier.read` | view suppliers | ● | ● | – | ● |
| `supplier.manage` | create/edit suppliers, price history | ● | ● | – | – |
| `purchase.read` / `purchase.create` | view / create+edit draft purchase orders | ● | ● | – | ● (read) |
| `purchase.validate` | validate, send, cancel purchase orders | ● | ● | – | – |
| `receipt.read` / `receipt.create` | view / confirm goods receipts | ● | ● | – | ● (read) |
| `return.supplier.create` | supplier returns | ● | ● | – | – |
| `return.read` | view returns | ● | ● | ● | ● |
| `customer.read` | view customers, balances | ● | ● | ● | ● |
| `customer.manage` | create/edit customers | ● | ● | ● | – |
| `customer.price.manage` | customer-specific prices, categories | ● | ● | – | – |
| `customer.credit.manage` | change credit limit | ● | – | – | ● |
| `sale.read` | view sales documents | ● | ● | ● | ● |
| `sale.create` | create quotes, receipts, invoices, delivery notes | ● | ● | ● | – |
| `sale.discount` | discount above entitlement | ● | ● | – | – |
| `sale.cancel` | cancel a confirmed document (if OD-004 allows) | ● | ● | – | – |
| `sale.override_fefo` | deviate from FEFO (if OD-010 allows) | ● | ● | – | – |
| `return.customer.create` | customer returns and credit notes | ● | ● | – | – |
| `payment.read` / `statement.read` | view payments, account statements | ● | – | ● | ● |
| `payment.customer.create` | record customer payments | ● | – | ● | ● |
| `payment.supplier.create` | record supplier payments | ● | – | – | ● |
| `payment.reverse` | reverse a payment | ● | – | – | ● |
| `supplierinvoice.manage` | supplier invoices | ● | – | – | ● |
| `cash.open` | open/close **own** cash session | ● | – | ● | ● |
| `cash.manage` | all sessions, manual cash movements | ● | – | – | ● |
| `report.sales` / `report.stock` | sales and stock reports | ● | ● | – | ● |
| `report.finance` | receivables/payables/cash reports | ● | – | – | ● |
| `report.margin` | cost and margin figures | ● | ● | – | ● |

Rules: permissions are additive per role; a user has one role; permission codes are seeded by migration and are **not** created at runtime by users; the API never trusts a permission list sent by the client.

## 5. Endpoint security

- Default deny; explicit allow-list of public routes (`/api/auth/login`, and `/actuator/health` only on the internal interface).
- Each endpoint in `api.md` states its **Required permission**. The permission is on the service method; a controller test per endpoint asserts 401 (anonymous), 403 (wrong role), 2xx (right role).
- **Mass-assignment protection:** only DTO fields are bound; server-owned fields (status, totals, numbers, `quantity_base`, `unit_cost`, `created_by`) are never accepted from the client.
- **Idempotency:** `POST` operations that create numbered documents or money records accept an `Idempotency-Key` header (BR-SALE-016).
- **Pagination bounds:** maximum page size enforced; sort fields whitelisted (§8).

## 6. Frontend route protection (convenience only)

- Routes and menu items are rendered according to the permission list returned by `GET /api/auth/me`.
- A hidden button is never a security control; each action still calls an API that enforces the permission.
- On 401 the SPA clears local state and redirects to login; on 403 it shows a "not allowed" notice.
- The SPA never stores credentials in web storage (§2.3) and never embeds secrets in the bundle (build-time env variables are public).

## 7. Sensitive operations and audit

Audit is written by services in the **same transaction** as the action (BR-AUDIT-003). Each entry: user, action, entity, entity id, old values, new values (JSON), client IP, timestamp (BR-AUDIT-002).

| Action code | Operation | Basis |
|---|---|---|
| `PRICE_CHANGE` | product/unit/customer price changes, price override at sale | SRC |
| `INVOICE_CANCEL` | cancellation of a confirmed sales document | SRC (OD-004) |
| `STOCK_CORRECTION` | manual adjustment; inventory regularisation | SRC |
| `LOT_BLOCK`, `LOT_UNBLOCK`, `LOT_RECALL` | lot status actions | SCH (`recall_lot`) + REC |
| `RETURN_CREATE` | customer / supplier returns | REC |
| `PAYMENT_REVERSE` | reversal of a payment/cash entry | REC |
| `CREDIT_LIMIT_CHANGE` | customer credit-limit change | REC |
| `USER_CREATE/UPDATE/DEACTIVATE`, `ROLE_PERMISSION_CHANGE`, `PASSWORD_RESET` | identity administration | REC |
| `SETTING_CHANGE` | alert thresholds, VAT default, company data | REC |
| `BACKUP_RUN`, `BACKUP_DOWNLOAD` | backup operations | REC |
| `FEFO_OVERRIDE`, `DISCOUNT_OVERRIDE` | exceptions at sale | REC (OD-010, BR-SALE-007) |

Audit data is append-only: no API to edit/delete; DB triggers and privileges (SD-102, BR-SEC-006). Passwords, tokens and hashes are never written to audit values.

## 8. Input validation and injection prevention

| Threat | Control |
|---|---|
| **SQL injection** | Only Spring Data derived queries, JPQL with bind parameters, or native queries with named parameters. **No string concatenation** into SQL/JPQL anywhere. Dynamic sorting/filtering uses an allow-list of property names mapped to columns. `LIKE` patterns are escaped. Review rule in `development-workflow.md`. |
| **Bean validation** | Every request DTO is validated (`@Valid`); lengths match column sizes; decimals have scale/range limits; enums parsed strictly. Violations → `VALIDATION_FAILED` with field errors. |
| **Business validation** | Services re-validate everything that matters (the client is untrusted). |
| **XSS** | React escapes output; `dangerouslySetInnerHTML` is forbidden; PDF/HTML templates (printing) escape all variables; `Content-Security-Policy` restricts scripts. |
| **Mass assignment** | DTOs only; entities never bound from requests. |
| **Path traversal / file access** | No user-supplied file paths; backup download addresses a record id, not a path. |
| **Deserialisation** | Jackson default typing disabled; unknown properties rejected for write DTOs. |
| **Numeric abuse** | `BigDecimal` with explicit scale; negative/zero checks per BR; no floating-point money. |
| **CSRF** | Mandatory, cookie-to-header token (§2.3, ADR-036). |
| **Open redirect / SSRF** | No redirect or server-side fetch of user-supplied URLs. |

## 9. Data-level protection

- Database accounts (BR-SEC-006, `database-migration.md` §9): `migrator` (DDL) used only during deployment; `app` (DML) with **no UPDATE/DELETE/TRUNCATE on `stock_movements` and `audit_log`**; `backup` (read-only). The application never connects as `root`.
- Triggers + privileges + `@Immutable` mapping protect the ledger (ADR-008).
- Database not exposed beyond the application host/network segment.
- Personal data stored (customer name, phone, address; user names/emails) is limited to business needs; applicable data-protection law and retention periods are **not specified by the Spec** (OD-021 covers retention).

## 10. Secrets and configuration

| Secret | Handling |
|---|---|
| DB passwords, bootstrap administrator password (ADR-040), mail credentials (none planned) | Supplied via **environment variables** or an external secret store at runtime; **never** committed; `.env*` and key files in `.gitignore`; sample file `application-example.yml` contains placeholders only |
| Profiles | `dev`, `test`, `prod`; production values never in the repository |
| Rotation | Signing key and DB passwords rotatable without code change (key id / reconnect) |
| Build | Frontend build variables are **public** — no secrets in `VITE_*` |
| Backups | Contain all data; stored with restricted access and encryption at rest `[REC]`; backup file download is audited (BR-BKP-002) |
| Repository hygiene | Secret-scanning in CI (e.g. gitleaks) `[REC]`; commit rule in `CLAUDE.md` |

## 11. Transport, CORS and headers

| Topic | Decision |
|---|---|
| TLS | HTTPS only in production (terminated at reverse proxy); HSTS enabled |
| CORS | Production: frontend and API on the **same origin** behind the reverse proxy ⇒ CORS disabled. Development: Vite dev-server proxies `/api` (no CORS) or an explicit allow-list of `http://localhost:<vite-port>`; **never `*` with credentials** |
| Headers | `Content-Security-Policy` (self + required font CDN decisions in `frontend.md`), `X-Content-Type-Options: nosniff`, `Referrer-Policy`, `X-Frame-Options: DENY`/`frame-ancestors 'none'`, `Cache-Control: no-store` on API responses with personal/financial data |
| Rate limiting | Login endpoint throttled per username+IP (BR-SEC-005, ADR-038; limits in `app_settings`); coarse global limit at the proxy; `RATE_LIMITED` (429) |
| Client IP | Taken from `X-Forwarded-For` only when the request comes from the reverse proxy (production profile); otherwise the socket address. Used for throttling and `audit_log.ip_address` |
| Error responses | Problem Details without stack traces or SQL text; correlation `traceId` only |

## 12. Logging of security events

Security logger (separate category, structured JSON, no secrets) records:

- login success / failure (username attempted, IP, reason class), lockouts, logout
- password change / reset, role or permission changes
- authorization denials (user, endpoint, permission)
- audited sensitive actions (§7) — duplicated as log lines for the SIEM/ops view
- backup runs and downloads
- detected integrity failures (`LEDGER_MISMATCH`, trigger violations)

Never logged: passwords, tokens, hashes, full card/cheque details, full request bodies of auth endpoints. Retention follows OD-021.

## 13. Threat summary

| Threat | Mitigation |
|---|---|
| Stock tampering to hide theft | Immutable ledger, mandatory reasons, audit of adjustments, reconciliation job (BR-STOCK-003/006/007) |
| Price/discount fraud | `price.edit` / `sale.discount` permissions, audit of overrides |
| Invoice cancellation abuse | `sale.cancel` permission, reason, audit, number retained |
| Privilege escalation | Server-side method security, no client-supplied permissions, role changes audited |
| Credential stuffing / brute force | Throttling, generic errors, strong hashing |
| Session/token theft | HttpOnly cookies / in-memory token, short lifetimes, TLS, CSP |
| Injection (SQL/XSS) | §8 |
| Data exfiltration via backups | Restricted, encrypted, audited backups |
| Insider DB tampering | Least-privilege DB account, triggers, separate migrator account |
| Dependency vulnerabilities | Pinned versions, automated vulnerability scanning in CI `[REC]` |

## 14. Security test obligations

Defined in `testing.md` §7: authentication, authorization matrix per endpoint, inactive-user lockout, throttling, audit completeness, immutability (ledger update/delete rejected), SQL-injection probes on search and sort parameters, header/CORS checks, secret scanning.
