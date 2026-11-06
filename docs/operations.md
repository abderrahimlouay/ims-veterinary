# Operations runbook

> **For the operator** (the developer, under the maintenance contract, ADR-150), after an installation done by `deployment.md`. Every step is outside business hours unless it says otherwise. Values (names, dates, places) come from the deployment record (`deployment.md` §9).

## 1. Routine

| When | What | How |
|---|---|---|
| Each business day (automatic) | Backup at `backup_time` (02:00) | the backups page shows it; nothing to do (ADR-145) |
| Each week | Off-site copy (ADR-146) | someone at the pharmacy downloads the newest backup to the removable disk kept off the premises; the page warns after `backup_download_reminder_days` (7) |
| Each week | Health | backups page without a red warning; `/stock/integrity` without a mismatch (ADR-086); `journalctl -u ims --since -7d -p err` empty or explained; disk under 80 % (`df -h`) |
| Each month | Updates | distribution security updates (automatic) checked; the release's dependency scan (CI, ADR-155) green |
| Each quarter | Restore rehearsal | §4 on the spare machine, timed; result in the deployment record |
| 60 days before | Certificate expiry | §6 |

## 2. Release (ADR-151, README *Production release*)

1. Before: the release tag is green in CI (including the volume workflow when reports or migrations changed); read its migrations and the roadmap entry.
2. Outside business hours, announce the stop.
3. Backups page → **Run now** → success (or check today's backup succeeded).
4. `systemctl stop ims`.
5. Copy the new `ims.jar` over `/opt/ims/ims.jar` (keep the previous one as `ims.jar.previous`) and the new frontend `dist/` into `/srv/ims/frontend` (keep the previous folder).
6. Migrate: `set -a; . /etc/ims/ims.env; set +a; SPRING_PROFILES_ACTIVE=prod,migrate IMS_DB_MIGRATION_USERNAME=ims_migrator IMS_DB_MIGRATION_PASSWORD='<migration-password>' runuser -u ims -- java -jar /opt/ims/ims.jar` → `Migrate mode: schema at version …`.
7. `systemctl start ims`; `journalctl -u ims -f` until started; open the app, log in, a test search, the backups page.
8. **If step 6 fails:** do not start the new version. Restore the backup of step 3 (§4, steps 3–6), put back `ims.jar.previous` and the previous frontend, start, and report the failure.
9. **If the new version misbehaves after a successful migration:** a migration is never undone by editing it; restore the backup of step 3 and the previous version, then fix forward.

## 3. Incidents

| Symptom | First checks | Action |
|---|---|---|
| The application does not answer | `systemctl status ims`, `journalctl -u ims -n 200`, `systemctl status mysql nginx` | restart the failed service; a start refused with a database-account report (README, *Database accounts*) means a wrong variable in `/etc/ims/ims.env` |
| "Not secure" in the browser | the PC trusts the CA? certificate expired? | install `ims-ca.crt` on the PC (§5) or renew (§6) |
| Backup failed (red on the page) | its reason on the page; `df -h`; `mysqldump` present | fix the cause, **Run now**, check success |
| Integrity mismatch (`/stock/integrity`) | the lots listed | Critical defect (BR-STOCK-007): do not adjust stock by hand; keep the report, investigate from the movements |
| A user locked out | throttling (ADR-038) | wait the lockout period, or an Administrator resets the password (ends the user's sessions, ADR-036) |
| A lost or stolen PC | who used it | an Administrator resets those users' passwords (their sessions end at once, ADR-036); the PC holds only the CA's public certificate, so nothing else needs replacing |
| Server dead | — | §4 on the spare machine (RTO four business hours, ADR-150) |

## 4. Restore (README *Restoring a backup*; rehearsed by `BackupRestoreRehearsalTest`)

Target: sales possible again within **four business hours**, data up to the last backup (one business day at most, ADR-145).
1. Choose the backup: the newest successful one on the server, or the newest off-site copy if the server's disk is lost.
2. Prepare the machine (`deployment.md` §2–§5, without the first-administrator step): same release, MySQL with the empty schema and the three accounts.
3. `systemctl stop ims` if it runs.
4. Import with the migration account: `gunzip -c ims-backup-….sql.gz | mysql --host=127.0.0.1 --user=ims_migrator -p ims`.
5. Migrate mode (§2 step 6): validates the history, applies newer migrations if the release is newer, grants the application account.
6. `systemctl start ims`; check the integrity page, the last documents and cash sessions of the day of the backup.
7. Tell the pharmacy what was lost: every sale, payment and stock movement after the backup's time must be entered again from the paper records.
8. Write the time taken and any problem in the deployment record.

## 5. Adding or replacing a counter PC

Install Chrome or Edge, current version (ADR-159); install `ims-ca.crt` in the trusted root store; add the server's local name if DNS does not resolve it; set up its printer; open `https://<local name>` and print a test ticket. Write it in the deployment record.

## 6. Certificate renewal (ADR-152)

Sixty days before the expiry in the deployment record: on the operator's machine, sign a new server certificate with the CA (`deployment.md` §6 step 2), install it in `/etc/ims/tls/`, `nginx -t`, `systemctl reload nginx`, check from a PC, update the expiry date. The CA itself lasts ten years; replacing it means installing the new root on every PC first.

## 7. People and keys (ADR-153)

When an operator leaves: remove their SSH key and WireGuard peer, restart WireGuard, check they no longer connect. When a pharmacy user leaves: an Administrator deactivates the account (never deleted, the audit trail keeps their name). Passwords of the database accounts change only with a release window: change in MySQL, then in `/etc/ims/ims.env`, restart.

## 8. Before go-live (Phase 12)

**Go-live data (ADR-160):** no data comes from the old system. On the empty production database: the company profile, users and roles, reference lists, products (bulk load possible, ADR-091, then `ANALYZE TABLE`), suppliers and customers; then the stock on hand lot by lot as opening stock (`opening_stock_open` on, ADR-082) after a physical count, and `opening_stock_open` switched off; a first backup and its off-site copy. Amounts customers owed in the old system are not carried over by the application.


Not operations, but blocking: the accountant's answers on DR-040 (legal rounding), DR-041 (prescription-only products) and DR-043 (legal mentions and stamp duty), then the UAT sign-off (roadmap, Phase 12).
