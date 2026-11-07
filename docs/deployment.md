# Installation guide — the owner's Windows laptop

> **Scope:** installing IMS Vétérinaire on the store owner's Windows 10/11 laptop, used by the shop's other devices over the local network (ADR-165), the application serving HTTPS and the screens itself (ADR-166), backups copied to Google Drive (ADR-167). Day-to-day operation, updates and restores: `operations.md`. The scripts are in `deploy/windows`; they print their messages in French for the owner.
> **Not tested on a real laptop yet:** the scripts are checked for syntax in CI only. The first installation on another Windows PC is the rehearsal (`uat.md` §4.2, §4.3); correct this page and the scripts with what it shows.

## 1. What runs where

| Component | Where | Reached by |
|---|---|---|
| The application (Java 21), Windows service `ims` (WinSW), account `ims-svc` | the laptop, HTTPS on 443, HTTP 80 only redirects | the laptop and the shop's devices on the **Private** network (Windows firewall) |
| MySQL 8.4, Windows service `MySQL84` | the laptop, `127.0.0.1:3306` only | the application, the scripts |
| Backups | `C:\IMS\backups`, copied to a Google Drive for desktop folder | the backups page; Google Drive off the laptop |
| The shop's private CA (`ims-ca.key`) | the **developer's** machine, offline, never the laptop | — |

Folders: `C:\IMS\app` (JAR, WinSW and its file `ims-service.xml`, which holds the database passwords), `C:\IMS\config` (`server.p12`, the migration account for the scripts), `C:\IMS\backups`, `C:\IMS\logs`. Only administrators, SYSTEM and `ims-svc` can read them.

## 2. What the developer prepares (own machine)

1. **Release JAR:** from the release tag, `cd frontend && npm ci && npm run build`, then `cd backend && ./mvnw -B -Prelease package` → `backend/target/ims-<version>.jar` (the screens inside; README, *Serving and HTTPS*).
2. **The laptop's name and address:** the laptop's Windows name (Settings → System → About, e.g. `PHARMA-PC`) and a **fixed address** reserved for it in the shop's router (DHCP reservation, e.g. `192.168.1.20`).
3. **Private CA**, once, kept offline: `openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes -keyout ims-ca.key -out ims-ca.crt -subj "/CN=Pharmacie IMS CA"`.
4. **Server certificate**, two years, naming the laptop, `localhost` (the scripts check `https://localhost`) and the fixed address:
   ```
   openssl req -new -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=PHARMA-PC"
   openssl x509 -req -in server.csr -CA ims-ca.crt -CAkey ims-ca.key -CAcreateserial -days 730 -sha256 \
     -extfile <(printf "subjectAltName=DNS:PHARMA-PC,DNS:localhost,IP:192.168.1.20")
   openssl pkcs12 -export -in server.crt -inkey server.key -certfile ims-ca.crt -out server.p12
   ```
   The last command asks for the **`server.p12` password**: give it to the owner apart from the file. Write the expiry date in the deployment record (§8). Delete `server.key` after the export.
5. **WinSW** v2.12.0, `WinSW-x64.exe` from its GitHub releases page; check its SHA-256 against the release page.
6. Give the owner, on a USB key: the JAR, `server.p12`, `ims-ca.crt`, `WinSW-x64.exe` and the `deploy/windows` folder.

## 3. Prepare the laptop (owner, with the developer)

1. Windows up to date. **Active hours** (Settings → Windows Update → Advanced options) set to the opening hours, so Windows never restarts the laptop during them. **Disk encryption on (ADR-168, required):** the database holds customers' personal data. Windows Pro: Settings → Privacy & security → Device encryption, or Control Panel → BitLocker Drive Encryption → Turn on BitLocker for `C:`. Windows Home: Settings → Privacy & security → **Device encryption** → On (it needs the owner signed in with a Microsoft account, which keeps the recovery key). Save the **recovery key** in the owner's Microsoft account or print it, and keep it away from the laptop. Wait until encryption has finished. `install.ps1` refuses to install while a drive it uses is not protected; a laptop with neither option is upgraded to Pro or replaced before go-live.
2. **Java 21:** Eclipse Temurin 21 JDK (MSI, x64) with the options "Set JAVA_HOME" and "Add to PATH".
3. **MySQL 8.4 LTS:** MySQL Installer or the MSI, "Server only"; type "Server Computer", port 3306, a **strong root password** (password manager), Windows service `MySQL84` started at boot. Then, as administrator, edit `C:\ProgramData\MySQL\MySQL Server 8.4\my.ini`, section `[mysqld]`:
   ```
   bind-address=127.0.0.1
   log_bin_trust_function_creators=1
   default_time_zone='+00:00'
   ```
   and restart the service (`Restart-Service MySQL84`). `install.ps1` refuses to continue if these are not in force.
4. **Google Drive for desktop:** signed in with the owner's Google account, **2-step verification on** (ADR-167), "My Drive" set to **Mirror files** (Preferences → Google Drive), so the folder is a real folder the service can write to (e.g. `C:\Users\<owner>\Mon Drive`). Create `IMS-sauvegardes` inside it.
5. The shop's Wi-Fi set as a **Private** network (Settings → Network → the Wi-Fi → Network profile type: Private). Any other network stays Public: the application is then unreachable from it.

## 4. Install (`install.ps1`)

In PowerShell **opened as administrator**, from the owner's Windows account (the one that will run updates), in the folder of the scripts:

```
Set-ExecutionPolicy -Scope Process Bypass
.\install.ps1 -Jar E:\ims-1.0.0.jar -Keystore E:\server.p12 -CaCertificate E:\ims-ca.crt -WinSW E:\WinSW-x64.exe `
  -DriveFolder "C:\Users\<owner>\Mon Drive\IMS-sauvegardes"
```

It checks the drive is encrypted (ADR-168), asks for the MySQL root password and the `server.p12` password, then: checks Java 21 and MySQL; creates the folders; installs `ims-ca.crt` in the laptop's trusted roots; creates the schema and the three MySQL accounts (`ims_migrator`, `ims_app`, `ims_backup` on `127.0.0.1`, generated passwords; ADR-087) and keeps the migration account for the scripts, readable only by this Windows account; creates the Windows account `ims-svc` with access to `C:\IMS` and the Drive folder only; migrates the schema (ADR-151); installs and starts the service with a **first Administrator** (ADR-040), then removes the bootstrap variables and restarts; opens 443 and 80 on the Private profile; turns off sleep and the lid action on mains power. At the end it prints the first Administrator's temporary password and the generated passwords: **copy them into the password manager**, then close the window.

## 5. After the installation

1. On the laptop, `https://localhost`: log in as `admin` with the temporary password and choose a new one (forced).
2. The company profile (administration settings, ADR-140), users and roles (one account per person).
3. Backups page → **Run now**: success, column "Copie Google Drive" = Copiée; the file appears in Google Drive on the web within minutes.
4. **Each device** of the shop: Chrome or Edge, current version (ADR-159); install `ims-ca.crt` in "Trusted Root Certification Authorities" (Windows: double-click → Install → Local Machine; Android: Settings → Security → Install a certificate → CA); open `https://PHARMA-PC` (or `https://192.168.1.20`): no certificate warning, the login page; bookmark it; print a test ticket on its printer.
5. Go-live data (ADR-160, `operations.md` §8).

## 6. Acceptance on site

On the laptop and on one other device, in Chrome and in Edge (ADR-159):
- log in, a sale at the counter (cash and cheque), print a **ticket** on the thermal printer and an **A4 invoice** (the print frame under the Content-Security-Policy, ADR-157, ADR-166);
- a report with CSV and PDF;
- Run now: copied to Google Drive; download it once;
- close the lid on mains power: the other device keeps working; switch the laptop to a Public network: the other device can no longer reach it.

Then the **restore rehearsal** and the **update rehearsal** on another Windows PC (`operations.md` §2, §4), timed against the four-hour RTO.

## 7. Uninstall (another PC takes over)

`C:\IMS\app\ims-service.exe uninstall`; remove the firewall rule "IMS Vétérinaire (HTTPS)", the account `ims-svc`, MySQL and `C:\IMS` (after a last backup copied to Google Drive).

## 8. Deployment record (fill in at installation, keep with the developer)

| Item | Value |
|---|---|
| Laptop: model, Windows version, disk encryption (BitLocker or Device encryption, ADR-168) and where the recovery key is kept | |
| Windows name and fixed address; certificate expiry date | |
| Opening hours = Windows Update active hours (ADR-150, ADR-165) | |
| Google account of the copy (2-step verification on), Drive folder | |
| Replacement PC for a restore: which, where | |
| Accounts created (names only, never passwords) | |
| Devices with the CA installed; browsers and versions | |
| Printers (thermal and A4) and the devices they are on | |
| Release installed (tag) and date | |
