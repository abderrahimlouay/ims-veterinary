<#
.SYNOPSIS
    Installs IMS Vétérinaire on the owner's Windows laptop (docs/deployment.md §4; ADR-165…167).
.DESCRIPTION
    Run once, in PowerShell opened as administrator, from the Windows account of the owner (the one that will run
    update.ps1), after Java 21 and MySQL 8.4 are installed (docs/deployment.md §2-§3). It creates the folders, the
    database and its three accounts with generated passwords, the Windows account of the service, the WinSW service,
    the firewall rule (Private network only), the power settings, migrates the schema, starts the application with a
    first Administrator and checks it answers over HTTPS. It reports whether the drives are encrypted, recommended but not required (ADR-169).
.PARAMETER Jar
    The release JAR (built with -Prelease, the screens inside; README, Serving and HTTPS).
.PARAMETER Keystore
    server.p12: the server certificate signed by the shop's CA, with its key (docs/deployment.md §1).
.PARAMETER CaCertificate
    ims-ca.crt: the CA's public certificate, installed in this laptop's trusted roots.
.PARAMETER WinSW
    WinSW-x64.exe (v2.12.0), the service wrapper (docs/deployment.md §2).
.PARAMETER DriveFolder
    The folder inside Google Drive for desktop that receives a copy of each backup (ADR-167),
    e.g. 'C:\Users\Owner\Mon Drive\IMS-sauvegardes'. Google Drive must be set to "Mirror files".
.PARAMETER MySqlService
    The Windows service of MySQL: MySQL84 if it exists, otherwise MySQL80 (ADR-178).
#>
param(
    [Parameter(Mandatory)] [string] $Jar,
    [Parameter(Mandatory)] [string] $Keystore,
    [Parameter(Mandatory)] [string] $CaCertificate,
    [Parameter(Mandatory)] [string] $WinSW,
    [Parameter(Mandatory)] [string] $DriveFolder,
    [string] $MySqlService = '',
    [string] $AdminUsername = 'admin'
)

. "$PSScriptRoot\ims-common.ps1"
Assert-Administrator
if (-not $MySqlService) {
    $MySqlService = @('MySQL84', 'MySQL80') | Where-Object { Get-Service $_ -ErrorAction SilentlyContinue } |
        Select-Object -First 1
    if (-not $MySqlService) {
        throw 'Service Windows de MySQL introuvable (MySQL84 ou MySQL80) : indiquez-le avec -MySqlService.'
    }
}

if (Get-Service $ImsService -ErrorAction SilentlyContinue) {
    throw 'Le service ims existe déjà : pour une nouvelle version utilisez update.ps1 (docs/operations.md §2).'
}
Write-Step 'Chiffrement du disque (conseillé, ADR-169)'
Show-DiskEncryption @($ImsRoot, $env:ProgramData, $DriveFolder)
Assert-PortsFree
$java = Find-Java
Find-MySqlBin | Out-Null
foreach ($file in @($Jar, $Keystore, $CaCertificate, $WinSW)) {
    if (-not (Test-Path $file)) { throw "Fichier introuvable : $file" }
}

# The server.p12 password is checked before anything changes (ADR-179): a mistyped one (e.g. an Arabic keyboard
# layout) would otherwise leave the service failing to start at the end of the installation.
Write-Step 'Mot de passe du certificat (server.p12)'
$check = Join-Path $env:TEMP 'ims-p12-check.txt'
[void] (Invoke-Program $java @('-XshowSettings:properties', '-version') $check)
$javaHome = (Get-Content "$check.err" | Where-Object { $_ -match '^\s*java\.home = ' } | Select-Object -First 1) -replace '^\s*java\.home = ', ''
$keytool = Join-Path ([string] $javaHome).Trim() 'bin\keytool.exe'
if (-not (Test-Path $keytool)) { throw "keytool introuvable ($keytool) : installez le JDK Temurin 21 (docs/deployment.md §3)." }
$keystorePassword = $null
for ($try = 1; $try -le 3 -and $null -eq $keystorePassword; $try++) {
    $candidate = (Get-Credential -UserName 'server.p12' -Message 'Mot de passe du fichier server.p12 (donné avec le certificat). Clavier en français ou anglais, pas en arabe.').GetNetworkCredential().Password
    $env:IMS_P12_CHECK = $candidate
    $valid = (Invoke-Program $keytool @('-list', '-keystore', "`"$Keystore`"", '-storetype', 'PKCS12', '-storepass:env', 'IMS_P12_CHECK') $check) -eq 0
    Remove-Item Env:\IMS_P12_CHECK
    if ($valid) { $keystorePassword = $candidate } else { Write-Warning "Mot de passe de server.p12 incorrect (essai $try sur 3)." }
}
Remove-Item $check, "$check.err" -Force -ErrorAction SilentlyContinue
if ($null -eq $keystorePassword) {
    throw 'Mot de passe de server.p12 incorrect 3 fois : rien n''a été modifié. Vérifiez la disposition du clavier et relancez.'
}

Write-Step 'Dossiers'
foreach ($dir in @($ImsApp, $ImsBackups, $ImsLogs, $ImsConfig, $DriveFolder)) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}
Copy-Item $Jar $ImsJar -Force
Copy-Item $Keystore (Join-Path $ImsConfig 'server.p12') -Force
Copy-Item $WinSW (Join-Path $ImsApp 'ims-service.exe') -Force

Write-Step 'Certificat de l''autorité de la pharmacie (racines de confiance de cet ordinateur)'
Import-Certificate -FilePath $CaCertificate -CertStoreLocation Cert:\LocalMachine\Root | Out-Null

Write-Step 'Base de données et comptes MySQL'
$root = Get-Credential -UserName 'root' -Message 'Mot de passe root de MySQL (choisi à l''installation de MySQL)'
$rootPassword = $root.GetNetworkCredential().Password
function Get-MySqlSettings {
    $settings = Join-Path $env:TEMP 'ims-mysql-settings.txt'
    Invoke-MySqlTool 'mysql.exe' 'root' $rootPassword @('--batch', '--skip-column-names', '--execute',
        'SELECT @@bind_address, @@log_bin_trust_function_creators, @@version, @@persisted_globals_load') > $settings
    $values = (Get-Content $settings -Raw).Trim() -split "`t"
    Remove-Item $settings -Force
    $values
}
$actual = Get-MySqlSettings
# MySQL 8.4.10 refuses the column name "manual" of migration V09_007: 8.4.11 or later, or 8.0.30 or later (ADR-178,
# docs/deployment.md §3).
$mysqlVersion = [regex]::Match($actual[2], '^8\.(0|4)\.(\d+)')
$mysqlMinimum = @{ '0' = 30; '4' = 11 }
if (-not $mysqlVersion.Success -or
        [int] $mysqlVersion.Groups[2].Value -lt $mysqlMinimum[$mysqlVersion.Groups[1].Value]) {
    throw "MySQL doit être 8.4.11 ou plus récent (8.4.x), ou 8.0.30 ou plus récent (8.0.x) ; trouvé : $($actual[2])"
}
# The settings of docs/deployment.md §3, written by the installer itself into the my.ini of the MySQL service when
# they are not in force (ADR-179): bind_address cannot be persisted by SET PERSIST in MySQL 8.0.
if ($actual[0] -ne '127.0.0.1' -or $actual[1] -ne '1') {
    $pathName = (Get-CimInstance Win32_Service -Filter "Name='$MySqlService'").PathName
    $myIni = [regex]::Match($pathName, '--defaults-file=(?:"([^"]+)"|(\S+))').Groups | Select-Object -Skip 1 |
        Where-Object { $_.Success } | ForEach-Object { $_.Value } | Select-Object -First 1
    if (-not $myIni -or -not (Test-Path $myIni)) {
        throw "Fichier my.ini du service $MySqlService introuvable ($pathName) : ajoutez les réglages à la main (docs/deployment.md §3)."
    }
    Write-Host "Réglages MySQL (bind-address=$($actual[0]), log_bin_trust_function_creators=$($actual[1])) : écrits dans $myIni, redémarrage de $MySqlService"
    Copy-Item $myIni "$myIni.avant-ims" -Force
    $required = @('bind-address=127.0.0.1', 'log_bin_trust_function_creators=1', "default_time_zone='+00:00'")
    $lines = New-Object System.Collections.Generic.List[string]
    $section = ''
    foreach ($line in [IO.File]::ReadAllLines($myIni)) {
        if ($line -match '^\s*\[(.+)\]\s*$') {
            $section = $Matches[1].Trim().ToLowerInvariant()
            $lines.Add($line)
            if ($section -eq 'mysqld') { $required | ForEach-Object { $lines.Add($_) } }
            continue
        }
        if ($section -eq 'mysqld' -and $line -match '^\s*(bind[-_]address|log[-_]bin[-_]trust[-_]function[-_]creators|default[-_]time[-_]zone)\s*=') {
            $lines.Add("# $line")
            continue
        }
        $lines.Add($line)
    }
    if (-not ($lines -contains '[mysqld]')) {
        $lines.Add('[mysqld]'); $required | ForEach-Object { $lines.Add($_) }
    }
    [IO.File]::WriteAllLines($myIni, $lines, (New-Object Text.UTF8Encoding $false))
    Restart-Service $MySqlService
    $ready = $false
    for ($i = 0; $i -lt 30 -and -not $ready; $i++) {
        Start-Sleep -Seconds 2
        try { $actual = Get-MySqlSettings; $ready = $true } catch { }
    }
    if (-not $ready) {
        Copy-Item "$myIni.avant-ims" $myIni -Force
        Restart-Service $MySqlService
        throw "MySQL ne répond plus après le redémarrage de $MySqlService : my.ini a été remis comme avant ; ajoutez les réglages à la main (docs/deployment.md §3)."
    }
}
if ($actual[0] -ne '127.0.0.1') {
    throw "MySQL écoute toujours sur « $($actual[0]) » au lieu de 127.0.0.1 (persisted_globals_load=$($actual[3])) : ajoutez bind-address=127.0.0.1 sous [mysqld] dans my.ini, redémarrez $MySqlService (docs/deployment.md §3)."
}
if ($actual[1] -ne '1') {
    throw "log_bin_trust_function_creators vaut toujours $($actual[1]) : ajoutez log_bin_trust_function_creators=1 sous [mysqld] dans my.ini, redémarrez $MySqlService (docs/deployment.md §3)."
}
$appPassword = New-Secret
$migrationPassword = New-Secret
$backupPassword = New-Secret
Invoke-MySqlTool 'mysql.exe' 'root' $rootPassword @('--execute', @"
CREATE DATABASE $ImsSchema CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'ims_migrator'@'127.0.0.1' IDENTIFIED BY '$migrationPassword';
GRANT ALL PRIVILEGES ON $ImsSchema.* TO 'ims_migrator'@'127.0.0.1' WITH GRANT OPTION;
CREATE USER 'ims_app'@'127.0.0.1' IDENTIFIED BY '$appPassword';
CREATE USER 'ims_backup'@'127.0.0.1' IDENTIFIED BY '$backupPassword';
GRANT SELECT, SHOW VIEW, TRIGGER, LOCK TABLES ON $ImsSchema.* TO 'ims_backup'@'127.0.0.1';
GRANT SHOW_ROUTINE ON *.* TO 'ims_backup'@'127.0.0.1';
"@)
$migration = New-Object pscredential('ims_migrator', (ConvertTo-SecureString $migrationPassword -AsPlainText -Force))
# Readable only by this Windows account (DPAPI): update.ps1 and restore.ps1 use it.
$migration | Export-Clixml $MigrationCredential

Write-Step 'Compte Windows du service (ims-svc)'
# Windows' complexity rule wants three kinds of character: drawn again until upper case, lower case and a digit are in.
do { $servicePassword = New-Secret 32 } until (
    $servicePassword -cmatch '[A-Z]' -and $servicePassword -cmatch '[a-z]' -and $servicePassword -match '[0-9]')
New-LocalUser -Name 'ims-svc' -Password (ConvertTo-SecureString $servicePassword -AsPlainText -Force) `
    -PasswordNeverExpires -UserMayNotChangePassword -Description 'IMS Vétérinaire (service)' | Out-Null
Set-Rights $ImsRoot @('/inheritance:r', '/grant:r', "${Administrators}:(OI)(CI)F", "${LocalSystem}:(OI)(CI)F",
    'ims-svc:(OI)(CI)RX')
foreach ($dir in @($ImsBackups, $ImsLogs)) {
    Set-Rights $dir @('/grant:r', 'ims-svc:(OI)(CI)M')
}
Set-Rights $DriveFolder @('/grant', 'ims-svc:(OI)(CI)M')
Set-Rights $ImsConfig @('/inheritance:r', '/grant:r', "${Administrators}:(OI)(CI)F", "${LocalSystem}:(OI)(CI)F",
    'ims-svc:(OI)(CI)R', "${env:USERNAME}:(OI)(CI)F")

Write-Step 'Migration du schéma (ADR-151)'
$environment = [ordered] @{
    SPRING_PROFILES_ACTIVE = 'prod'
    IMS_DB_URL = "jdbc:mysql://127.0.0.1:3306/$ImsSchema"
    IMS_DB_USERNAME = 'ims_app'
    IMS_DB_PASSWORD = $appPassword
    IMS_DB_APPLICATION_HOST = '127.0.0.1'
    IMS_TLS_KEYSTORE = (Join-Path $ImsConfig 'server.p12')
    IMS_TLS_KEYSTORE_PASSWORD = $keystorePassword
    IMS_BACKUP_DIR = $ImsBackups
    IMS_BACKUP_COPY_DIR = $DriveFolder
    IMS_BACKUP_DB_USERNAME = 'ims_backup'
    IMS_BACKUP_DB_PASSWORD = $backupPassword
    IMS_BACKUP_MYSQLDUMP = (Join-Path (Find-MySqlBin) 'mysqldump.exe')
}
Invoke-Migrate $java $ImsJar $migration $environment

Write-Step 'Service Windows'
$adminPassword = New-Secret 16
function Write-ServiceFile([System.Collections.IDictionary] $Values) {
    $envLines = ($Values.GetEnumerator() | ForEach-Object {
        "  <env name=`"$($_.Key)`" value=`"$([Security.SecurityElement]::Escape([string] $_.Value))`"/>"
    }) -join "`r`n"
    $xml = @"
<!-- IMS Vétérinaire as a Windows service (WinSW v2; docs/deployment.md, ADR-165). Written by install.ps1;
     holds the database passwords: readable by administrators, SYSTEM and ims-svc only. -->
<service>
  <id>$ImsService</id>
  <name>IMS Vétérinaire</name>
  <description>Stock, ventes et finances de la pharmacie vétérinaire (https://localhost)</description>
  <executable>$java</executable>
  <arguments>-Xms512m -Xmx1536m -jar "$ImsJar"</arguments>
  <workingdirectory>$ImsApp</workingdirectory>
  <startmode>Automatic</startmode>
  <depend>$MySqlService</depend>
  <stoptimeout>60 sec</stoptimeout>
  <onfailure action="restart" delay="10 sec"/>
  <logpath>$ImsLogs</logpath>
  <log mode="roll-by-size">
    <sizeThreshold>10240</sizeThreshold>
    <keepFiles>8</keepFiles>
  </log>
  <serviceaccount>
    <domain>.</domain>
    <user>ims-svc</user>
    <password>$servicePassword</password>
    <allowservicelogon>true</allowservicelogon>
  </serviceaccount>
$envLines
</service>
"@
    Set-Content -Path (Join-Path $ImsApp 'ims-service.xml') -Value $xml -Encoding UTF8
}
$first = [ordered] @{}
foreach ($key in $environment.Keys) { $first[$key] = $environment[$key] }
$first['IMS_BOOTSTRAP_ADMIN_USERNAME'] = $AdminUsername
$first['IMS_BOOTSTRAP_ADMIN_PASSWORD'] = $adminPassword
$first['IMS_BOOTSTRAP_ADMIN_FULL_NAME'] = 'Administrateur'
Write-ServiceFile $first
& (Join-Path $ImsApp 'ims-service.exe') install
if ($LASTEXITCODE -ne 0) { throw 'Installation du service refusée (voir C:\IMS\logs).' }
Start-Service $ImsService
Wait-Healthy
# ADR-040: the first Administrator exists now; the bootstrap variables are removed for good.
Stop-Service $ImsService
Write-ServiceFile $environment
Start-Service $ImsService
Wait-Healthy

Write-Step 'Pare-feu : HTTPS (443) et redirection (80) sur le réseau Privé seulement (ADR-166)'
New-NetFirewallRule -DisplayName 'IMS Vétérinaire (HTTPS)' -Direction Inbound -Protocol TCP -LocalPort 443, 80 `
    -Profile Private -Action Allow | Out-Null

Write-Step 'Alimentation : pas de veille sur secteur, couvercle fermé sans effet sur secteur (ADR-165)'
& powercfg /change standby-timeout-ac 0
& powercfg /change hibernate-timeout-ac 0
& powercfg /setacvalueindex SCHEME_CURRENT 4f971e89-eebd-4455-a8de-9e59040e7347 5ca83367-6e45-459f-a27b-476b1d01c936 0
& powercfg /setactive SCHEME_CURRENT

Write-Host ''
Write-Host 'Installation terminée : https://localhost' -ForegroundColor Green
Write-Host 'À noter MAINTENANT dans le gestionnaire de mots de passe (puis fermer cette fenêtre) :' -ForegroundColor Yellow
Write-Host "  Premier administrateur : $AdminUsername / $adminPassword  (mot de passe à changer à la première connexion)"
Write-Host "  MySQL ims_migrator : $migrationPassword"
Write-Host "  MySQL ims_app : $appPassword"
Write-Host "  MySQL ims_backup : $backupPassword"
Write-Host "  Compte Windows ims-svc : $servicePassword"
Write-Host 'Puis : réseau Wi-Fi de la boutique en profil Privé, Google Drive en mode "Mettre en miroir", vérification en deux étapes du compte Google (docs/deployment.md §5).'
