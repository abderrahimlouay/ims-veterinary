# Installation guide — production server

> **Being rewritten (2026-11-06):** the application now runs on the store owner's Windows laptop (ADR-165), serves HTTPS and the screens itself without nginx (ADR-166) and copies its backups to Google Drive (ADR-167). The Linux, nginx, VPN and SSH steps below no longer apply; the Windows guide replaces this page in the next Phase 12 slice.

> **Scope:** installing IMS Vétérinaire on the pharmacy's on-premises server, as decided in ADR-150 (on premises, business hours, RTO four business hours, operated by the developer), ADR-151 (migrate mode), ADR-152 (HTTPS with a private CA), ADR-153 (VPN and SSH keys), ADR-157 (reverse proxy) and ADR-159 (Chrome and Edge). Day-to-day operations, releases and restores are in `operations.md`.
>
> Commands are **examples** for a Debian or Ubuntu server with systemd; the distribution, the hardware and the names are chosen at installation and written in the **deployment record** (§9). Nothing here contains a real password: every secret is created on the server and kept there.

## 1. What runs where

| Component | Where | Reached by |
|---|---|---|
| nginx (TLS, frontend, rate limit) | the server, ports 80 → 443 | the counter and office PCs on the local network |
| The application (Java 21) | the server, `127.0.0.1:8080`, service `ims` | nginx only |
| MySQL 8.4 LTS | the server, `127.0.0.1:3306` | the application, the migrate mode, the backups |
| Backups | `/var/lib/ims/backups` on the server; copies downloaded off site | the backups page (`backup.manage`) |
| WireGuard VPN, SSH | the server | the operator, from outside (ADR-153) |

Hardware: a server or mini-PC with a UPS (ADR-150), and a **spare machine** or a replacement obtainable within four business hours. Disk: the database (a few GB after five years at the target volume, ADR-158) and 30 compressed backups (ADR-145).

## 2. Base system

1. Install the distribution's current long-term release, apply its updates and enable its automatic security updates.
2. Set the clock to **UTC** with time synchronisation on (`timedatectl set-timezone UTC`, `timedatectl set-ntp true`); the business time zone is a setting of the application (ADR-033).
3. Give the server a fixed address on the local network and a local name, for example `ims.pharmacie.lan`, resolvable by the PCs (router DNS or hosts files).
4. Firewall: allow 80 and 443 from the local network, the VPN port (UDP, ADR-153) from the internet, SSH only from the local network and the VPN; deny everything else. Example with `ufw`: `ufw default deny incoming`, `ufw allow from <lan>/24 to any port 80,443 proto tcp`, `ufw allow <vpn-port>/udp`, `ufw allow from <lan>/24 to any port 22 proto tcp`, `ufw allow from <vpn-subnet> to any port 22 proto tcp`, `ufw enable`.
5. Packages: Java 21 (Temurin or the distribution's OpenJDK 21), MySQL 8.4 server and client (`mysqldump` is used by the backups), nginx, WireGuard, OpenSSH server.

## 3. Remote access (ADR-153)

1. WireGuard: one interface on the server, one peer per operator, keys generated on each side, the private keys never copied elsewhere. Only the VPN port is exposed.
2. SSH (`/etc/ssh/sshd_config.d/ims.conf`): `PasswordAuthentication no`, `KbdInteractiveAuthentication no`, `PermitRootLogin no`, `ListenAddress` limited to the local and VPN addresses. The operator's public keys go in their own account's `authorized_keys` and are listed in the deployment record.
3. Check from outside: the VPN connects, SSH works only through it, a password login is refused.

## 4. Database

1. In `/etc/mysql/mysql.conf.d/ims.cnf`: `bind-address = 127.0.0.1`, `log_bin_trust_function_creators = 1` (the triggers, ADR-017), `default_time_zone = '+00:00'`. Restart MySQL.
2. As the MySQL administrator, create the schema and the three accounts with **strong, different** passwords kept only in the password manager and in `/etc/ims/ims.env`:

```sql
CREATE DATABASE ims CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'ims_migrator'@'127.0.0.1' IDENTIFIED BY '<migration-password>';
GRANT ALL PRIVILEGES ON ims.* TO 'ims_migrator'@'127.0.0.1' WITH GRANT OPTION;
CREATE USER 'ims_app'@'127.0.0.1' IDENTIFIED BY '<application-password>';
CREATE USER 'ims_backup'@'127.0.0.1' IDENTIFIED BY '<backup-password>';
GRANT SELECT, SHOW VIEW, TRIGGER, LOCK TABLES ON ims.* TO 'ims_backup'@'127.0.0.1';
GRANT SHOW_ROUTINE ON *.* TO 'ims_backup'@'127.0.0.1';
```

The accounts are for `127.0.0.1`: the application, the migrate mode and `mysqldump` connect over TCP to that address (a `localhost` account would only match the Unix socket). The application account gets no right here: the migrate mode grants it, to the host given by `IMS_DB_APPLICATION_HOST` (§5; `%` when unset, ADR-087).

## 5. Application

1. A system user without login: `useradd --system --home /opt/ims --shell /usr/sbin/nologin ims`.
2. Folders: `/opt/ims` (the JAR, owned by root, readable by `ims`), `/var/lib/ims/backups` (owned by `ims`, mode 700), `/etc/ims` (root, mode 700).
3. Copy the release JAR as `/opt/ims/ims.jar` (built by `./mvnw -B package` from the release tag).
4. `/etc/ims/ims.env`, owned by root, mode 600 — the names are those of `.env.example`:

```
SPRING_PROFILES_ACTIVE=prod
IMS_DB_URL=jdbc:mysql://127.0.0.1:3306/ims
IMS_DB_USERNAME=ims_app
IMS_DB_PASSWORD=<application-password>
IMS_DB_APPLICATION_HOST=127.0.0.1
IMS_BACKUP_DIR=/var/lib/ims/backups
IMS_BACKUP_DB_USERNAME=ims_backup
IMS_BACKUP_DB_PASSWORD=<backup-password>
```

The migration account is **not** in this file (ADR-151): it is given only to the migrate run.
5. First migration, as root: `set -a; . /etc/ims/ims.env; set +a; SPRING_PROFILES_ACTIVE=prod,migrate IMS_DB_MIGRATION_USERNAME=ims_migrator IMS_DB_MIGRATION_PASSWORD='<migration-password>' runuser -u ims -- java -jar /opt/ims/ims.jar`. It logs `Migrate mode: schema at version …` and exits.
6. First Administrator (ADR-040): for the first start only, add `IMS_BOOTSTRAP_ADMIN_USERNAME` and `IMS_BOOTSTRAP_ADMIN_PASSWORD` (≥ 12 characters) to `/etc/ims/ims.env`; start; log in, change the password as asked; **remove both lines** and restart.
7. Service: copy `deploy/systemd/ims.service` to `/etc/systemd/system/`, then `systemctl daemon-reload`, `systemctl enable --now ims`, `journalctl -u ims -f` until the start is complete; `curl -s http://127.0.0.1:8080/actuator/health` answers `{"status":"UP"}`.

## 6. HTTPS and the frontend (ADR-152, ADR-157)

1. **Private CA**, on the operator's own machine, never on the server: `openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes -keyout ims-ca.key -out ims-ca.crt -subj "/CN=Pharmacie IMS CA"`. Keep `ims-ca.key` offline (encrypted storage).
2. **Server certificate** for the local name, signed by that CA, with the name in `subjectAltName`: create a key and a request (`openssl req -new -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=ims.pharmacie.lan"`), sign it for two years (`openssl x509 -req -in server.csr -CA ims-ca.crt -CAkey ims-ca.key -CAcreateserial -days 730 -sha256 -extfile <(printf "subjectAltName=DNS:ims.pharmacie.lan")`). Install `server.crt` and `server.key` in `/etc/ims/tls/` (key readable by root and nginx only). Write the expiry date in the deployment record.
3. **Each PC:** install `ims-ca.crt` in the trusted root store (Windows: certificate manager, "Trusted Root Certification Authorities"; Chrome and Edge use it, ADR-159).
4. **nginx:** `deploy/nginx/ims.conf` → `/etc/nginx/conf.d/ims.conf` (replace `ims.pharmacie.lan` by the local name), `deploy/nginx/ims-security-headers.conf` → `/etc/nginx/snippets/`; remove the default site; `nginx -t`; `systemctl reload nginx`.
5. **Frontend:** `npm ci && npm run build` in `frontend/` from the release tag; copy `dist/` to `/srv/ims/frontend` (readable by nginx).
6. Open `https://ims.pharmacie.lan` on a counter PC: no certificate warning, the login page in French.

## 7. Backups (ADR-144…146)

The service already has the backup variables. Check on the backups page: **Run now** gives a success with a file in `/var/lib/ims/backups`; the warnings disappear except "no download" until the first off-site copy. Set the off-site routine (who downloads, how often, where the disk is kept) in the deployment record.

## 8. Acceptance on site

On a counter PC in Chrome and on one in Edge (ADR-159):
- log in, a sale at the counter (cash and cheque), print a **ticket** on the thermal printer and an **A4 invoice** (the print frame under the Content-Security-Policy, ADR-157);
- a report with CSV and PDF;
- Run now, then download the backup;
- from outside, through the VPN: SSH works; without the VPN nothing answers.

Then the **restore rehearsal** on the spare machine (`operations.md` §4), timed against the four-hour RTO.

## 9. Deployment record (fill in at installation, keep with the operator)

| Item | Value |
|---|---|
| Server: hardware, distribution and version, disk, UPS | |
| Local name and address; certificate expiry date | |
| Spare machine: where, how prepared | |
| Business hours (ADR-150) | |
| Accounts created (names only, never passwords) | |
| Operator SSH keys and VPN peers (names, fingerprints) | |
| Off-site backup routine: who, how often, where | |
| PCs with the CA installed; browsers and versions | |
| Printers (thermal and A4) and the PCs they are on | |
| Release installed (tag) and date | |
