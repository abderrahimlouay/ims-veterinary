# Operations runbook

> **For the owner and the developer** (ADR-165: the owner runs the scripts, the developer helps by phone), after an installation done by `deployment.md`. Every step is outside opening hours unless it says otherwise. Values (names, dates) come from the deployment record (`deployment.md` §8). The scripts are in `deploy/windows`, run in PowerShell **opened as administrator** from the owner's Windows account.

## 1. Routine

| When | What | How |
|---|---|---|
| Each opening day | The laptop in the shop, plugged in and on, before the other devices start (ADR-165) | the lid may be closed on mains power; never asleep, never taken away during opening hours |
| Each business day (automatic) | Backup at `backup_time` (02:00), copied to Google Drive (ADR-145, ADR-167) | the laptop must be on at that time, or press **Run now** in the morning; Google Drive running and signed in |
| Each week | Health | backups page without a red warning (a failed copy shows there); `/stock/integrity` without a mismatch (ADR-086); free disk over 20 % |
| Each month | Updates | Windows updates installed outside opening hours (active hours set, `deployment.md` §3); a new release only through §2 |
| Each quarter | Restore rehearsal | §4 on another Windows PC, timed; result in the deployment record |
| 60 days before | Certificate expiry | §6 |

## 2. Update to a new version (ADR-151, `update.ps1`)

1. The developer: the release tag green in CI (including the volume workflow when reports or migrations changed); builds the release JAR (`deployment.md` §2) and sends it with a note of what changes.
2. The owner, after closing time, all devices logged out: `.\update.ps1 -Jar <path>\ims-<version>.jar`.
3. The script saves the database (`C:\IMS\backups\avant-mise-a-jour-….sql`), stops the service, keeps the previous JAR (`ims.jar.previous`), migrates, starts and checks `https://localhost` answers.
4. **If it fails** it puts the previous version and the saved data back by itself and says "Appelez le développeur": the shop works as before; the developer fixes forward (a migration is never undone by editing it).
5. Then log in, a test search, the backups page.

## 3. Incidents

| Symptom | First checks | Action |
|---|---|---|
| No device reaches the application | the laptop on and awake? on the shop's Wi-Fi, profile **Private**? service `ims` running (`Get-Service ims, MySQL84`)? | start the stopped service (`Start-Service MySQL84; Start-Service ims`); logs in `C:\IMS\logs`; a start refused with a database-account report means a wrong value in `ims-service.xml` |
| Only the other devices cannot reach it | the Wi-Fi profile became Public? the router gave the laptop another address? | set Private; check the DHCP reservation |
| "Not secure" in the browser | the device trusts the CA? certificate expired? the address used is in the certificate? | install `ims-ca.crt` on the device (§5) or renew (§6); use the bookmarked address |
| Backup failed (red on the page) | its reason on the page; free disk | fix the cause, **Run now**, check success |
| Copy to Google Drive failed or none for a day | Google Drive running, signed in, in **Mirror** mode? the folder still there? | fix, **Run now**, check "Copiée" |
| Integrity mismatch (`/stock/integrity`) | the lots listed | Critical defect (BR-STOCK-007): do not adjust stock by hand; keep the report, call the developer |
| A user locked out | throttling (ADR-038) | wait the lockout period, or an Administrator resets the password (ends the user's sessions, ADR-036) |
| A device lost or stolen | who used it | an Administrator resets those users' passwords (their sessions end at once, ADR-036) |
| The laptop lost, stolen or dead | — | §4 on another Windows PC (RTO four business hours); change the owner's Google password; reset every user's password once restored |

## 4. Restore (`restore.ps1`; README *Restoring a backup*; rehearsed by `BackupRestoreRehearsalTest`)

Target: sales possible again within **four business hours**, data up to the last backup (one business day at most, ADR-145).
1. Choose the backup: the newest `ims-backup-….sql.gz` in Google Drive (any browser, download it), or in `C:\IMS\backups` if the laptop still works.
2. A replacement Windows PC: `deployment.md` §3 and §4 with the same release JAR (a new certificate if its name or address differs, §6, and `ims-ca.crt` already on the devices stays valid).
3. `.\restore.ps1 -Backup <path>\ims-backup-….sql.gz`: it saves the current data, replaces it with the backup, migrates to the installed version and starts; it prints the time taken.
4. Check the integrity page, the last documents and cash sessions of the day of the backup.
5. Tell the pharmacy what was lost: every sale, payment and stock movement after the backup's time must be entered again from the paper records.
6. Write the time taken and any problem in the deployment record.

## 5. Adding or replacing a device

Install Chrome or Edge, current version (ADR-159); install `ims-ca.crt` in the trusted root store; open `https://<laptop name>` (or its fixed address); set up its printer and print a test ticket. Write it in the deployment record.

## 6. Certificate renewal (ADR-152)

Sixty days before the expiry in the deployment record: the developer signs a new server certificate with the CA (`deployment.md` §2 step 4) and exports `server.p12` **with the same password**; the owner copies it over `C:\IMS\config\server.p12` and runs `Restart-Service ims`; check from a device; update the expiry date. The CA lasts ten years; replacing it means installing the new root on every device first.

## 7. People and passwords

When a pharmacy user leaves: an Administrator deactivates the account (never deleted, the audit trail keeps their name). The owner's Windows and Google passwords are the keys to the data: strong, not shared, 2-step verification on Google (ADR-167). The database passwords are in the password manager and in `C:\IMS\app\ims-service.xml`; change them only with the developer.

## 8. Before go-live (Phase 12)

**Go-live data (ADR-160):** no data comes from the old system. On the empty production database: the company profile, users and roles, reference lists, products (bulk load possible, ADR-091, then `ANALYZE TABLE`), suppliers and customers; then the stock on hand lot by lot as opening stock (`opening_stock_open` on, ADR-082) after a physical count, and `opening_stock_open` switched off; a first backup and its copy in Google Drive. Amounts customers owed in the old system are not carried over by the application.


Not operations, but blocking: the UAT sign-off (roadmap, Phase 12), including the restore check on the laptop before any real data (`uat.md` §4.2, ADR-170). DR-040, DR-041 and DR-043 are answered (ADR-171, ADR-172); if stamp duty applies, the administrator enters the accountant's figures in the settings (`stamp_duty_*`) before the first real sale.
