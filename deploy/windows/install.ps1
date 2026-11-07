<#
.SYNOPSIS
    Installs IMS Vétérinaire on the owner's Windows laptop (docs/deployment.md §4; ADR-165…167).
.DESCRIPTION
    Run once, in PowerShell opened as administrator, from the Windows account of the owner (the one that will run
    update.ps1), after Java 21 and MySQL 8.4 are installed (docs/deployment.md §2-§3). It creates the folders, the
    database and its three accounts with generated passwords, the Windows account of the service, the WinSW service,
    the firewall rule (Private network only), the power settings, migrates the schema, starts the application with a
    first Administrator and checks it answers over HTTPS. It refuses to start on a drive that is not encrypted (ADR-168).
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
    The Windows service of MySQL, MySQL84 by default.
#>
param(
    [Parameter(Mandatory)] [string] $Jar,
    [Parameter(Mandatory)] [string] $Keystore,
    [Parameter(Mandatory)] [string] $CaCertificate,
    [Parameter(Mandatory)] [string] $WinSW,
    [Parameter(Mandatory)] [string] $DriveFolder,
    [string] $MySqlService = 'MySQL84',
    [string] $AdminUsername = 'admin'
)

. "$PSScriptRoot\ims-common.ps1"
Assert-Administrator

if (Get-Service $ImsService -ErrorAction SilentlyContinue) {
    throw 'Le service ims existe déjà : pour une nouvelle version utilisez update.ps1 (docs/operations.md §2).'
}
Write-Step 'Chiffrement du disque (ADR-168)'
Assert-DiskEncrypted @($ImsRoot, $env:ProgramData, $DriveFolder)
$java = Find-Java
Find-MySqlBin | Out-Null
foreach ($file in @($Jar, $Keystore, $CaCertificate, $WinSW)) {
    if (-not (Test-Path $file)) { throw "Fichier introuvable : $file" }
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
$settings = Join-Path $env:TEMP 'ims-mysql-settings.txt'
Invoke-MySqlTool 'mysql.exe' 'root' $rootPassword @('--batch', '--skip-column-names', '--execute',
    'SELECT @@bind_address, @@log_bin_trust_function_creators, @@version') > $settings
$actual = (Get-Content $settings -Raw).Trim() -split "`t"
Remove-Item $settings -Force
if ($actual[0] -ne '127.0.0.1' -or $actual[1] -ne '1' -or -not $actual[2].StartsWith('8.4')) {
    throw "MySQL doit être 8.4 avec bind-address=127.0.0.1 et log_bin_trust_function_creators=1 dans my.ini (docs/deployment.md §3) ; trouvé : $actual"
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
$servicePassword = New-Secret 32
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
$keystorePassword = (Get-Credential -UserName 'server.p12' -Message 'Mot de passe du fichier server.p12 (donné avec le certificat)').GetNetworkCredential().Password
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
