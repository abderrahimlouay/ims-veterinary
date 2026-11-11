# CI rehearsal of the owner's scripts (deploy/windows) on a real Windows runner, in Windows PowerShell 5.1
# (.github/workflows/windows.yml; docs/testing.md; ADR-151, ADR-165..167, ADR-169). It runs the shipped scripts, unchanged:
#   1. install.ps1, as the owner starts it: lancer-installation.ps1 from an installer folder shaped like
#      deploy/bundle/make-bundle.sh's (it refuses a certificate made for another computer first); the runner's drive
#      is not encrypted, so install.ps1 must warn and go on (ADR-169);
#   2. a backup through the API, copied to the "Google Drive" folder (ADR-167);
#   3. update.ps1 with a JAR carrying one more migration;
#   4. update.ps1 with a JAR whose migration fails: the previous JAR and data must come back by themselves;
#   5. restore.ps1 with the Drive copy of step 2.
param(
    [Parameter(Mandatory)] [string] $JarA,
    [Parameter(Mandatory)] [string] $JarB,
    [Parameter(Mandatory)] [string] $JarFailing,
    [Parameter(Mandatory)] [string] $Keystore,
    [Parameter(Mandatory)] [string] $CaCertificate,
    [Parameter(Mandatory)] [string] $WinSW,
    [Parameter(Mandatory)] [string] $DriveFolder,
    [Parameter(Mandatory)] [string] $RootPassword,
    [Parameter(Mandatory)] [string] $KeystorePassword
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$shipped = Join-Path $repo 'deploy\windows'
$base = 'https://localhost'
$newAdminPassword = 'Ci-' + [Guid]::NewGuid().ToString('N')

function Step([string] $Message) { Write-Host "`n######## $Message" -ForegroundColor Magenta }
function Check([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw "CHECK FAILED: $Message" }
    Write-Host "  ok: $Message" -ForegroundColor Green
}

# install.ps1 asks twice with Get-Credential; a function of that name in this (global) scope answers instead of the
# prompt (functions come before cmdlets in PowerShell's command lookup). The values are read from this script's scope:
# a variable of the same name in install.ps1 (names ignore case) would otherwise hide them.
function global:Get-Credential([string] $UserName, [string] $Message) {
    $value = if ($UserName -eq 'root') { $script:RootPassword } else { $script:KeystorePassword }
    New-Object pscredential($UserName, (ConvertTo-SecureString $value -AsPlainText -Force))
}

# lancer-installation.ps1 asks for the Google Drive folder with Read-Host; answered the same way.
function global:Read-Host([string] $Prompt) { $DriveFolder }

# Runs a script as the owner does, without redirecting it (in Windows PowerShell 5.1 a redirection turns a program's
# stderr line into an error that 'Stop' makes fatal), and reads what it printed from a transcript.
function Invoke-Captured([scriptblock] $Block) {
    $transcript = Join-Path $env:RUNNER_TEMP ('transcript-' + [Guid]::NewGuid().ToString('N') + '.txt')
    Start-Transcript -Path $transcript | Out-Null
    $global:LASTEXITCODE = 0
    try { & $Block | Out-Host } finally { Stop-Transcript | Out-Null }
    [pscustomobject] @{ Output = (Get-Content $transcript -Raw); ExitCode = $global:LASTEXITCODE }
}

# The API with curl.exe (Schannel: it trusts the CA install.ps1 put in the machine's roots), cookies in a jar and the
# CSRF token sent back from its XSRF-TOKEN cookie (api.md, Authentication).
$jar = Join-Path $env:RUNNER_TEMP 'ims-cookies.txt'
function Get-Xsrf {
    $line = Get-Content $jar -ErrorAction SilentlyContinue | Where-Object { $_ -match "`tXSRF-TOKEN`t" } | Select-Object -Last 1
    if ($line) { ($line -split "`t")[-1] } else { '' }
}
function Invoke-Api([string] $Method, [string] $Path, [string] $Body = $null) {
    $arguments = @('--ssl-no-revoke', '-sS', '-X', $Method, '-b', $jar, '-c', $jar, '-H', "X-XSRF-TOKEN: $(Get-Xsrf)", '-w', "`n%{http_code}")
    if ($Body) {
        $bodyFile = Join-Path $env:RUNNER_TEMP 'ims-body.json'
        [IO.File]::WriteAllText($bodyFile, $Body, (New-Object Text.UTF8Encoding $false))
        $arguments += @('-H', 'Content-Type: application/json', '--data-binary', "@$bodyFile")
    }
    $raw = (& curl.exe @arguments "$base$Path") -join "`n"
    $index = $raw.LastIndexOf("`n")
    [pscustomobject] @{ Status = [int] $raw.Substring($index + 1); Body = $raw.Substring(0, [Math]::Max($index, 0)) }
}
function Connect-Api([string] $Password) {
    if (Test-Path $jar) { Remove-Item $jar -Force }
    [void] (Invoke-Api 'GET' '/api/auth/me')
    Invoke-Api 'POST' '/api/auth/login' (@{ username = 'admin'; password = $Password } | ConvertTo-Json)
}

function Get-Sha([string] $Path) { (Get-FileHash $Path -Algorithm SHA256).Hash }
function Assert-Healthy([string] $When) {
    $health = (curl.exe --ssl-no-revoke -sS "$base/actuator/health") -join "`n"
    Check ($health -match '"UP"') "the application answers UP over HTTPS $When"
    Check ((Get-Service ims).Status -eq 'Running') "service ims running $When"
}

Step '1. INSTALLER.cmd -> lancer-installation.ps1 -> install.ps1 (ADR-165, ADR-169)'
# The installer folder of deploy/bundle/make-bundle.sh and make-certificate.sh: JAR, certificates, WinSW, scripts.
$bundle = Join-Path $env:RUNNER_TEMP 'IMS-installateur'
New-Item -ItemType Directory -Force (Join-Path $bundle 'scripts') | Out-Null
Copy-Item (Join-Path $shipped '*.ps1') (Join-Path $bundle 'scripts')
Copy-Item $JarA (Join-Path $bundle 'ims-rehearsal.jar')
Copy-Item $Keystore (Join-Path $bundle 'server.p12')
Copy-Item $CaCertificate (Join-Path $bundle 'ims-ca.crt')
Copy-Item $WinSW (Join-Path $bundle 'WinSW-x64.exe')
$launcher = Join-Path $bundle 'scripts\lancer-installation.ps1'
$identity = Join-Path $bundle 'certificat.txt'
Set-Content $identity "NOM=AUTRE-PC`r`nADRESSE=127.0.0.1`r`nEXPIRE=-"
$refused = $null
try { & $launcher } catch { $refused = $_.Exception.Message }
Check ($refused -match "fait pour l'ordinateur AUTRE-PC") 'a certificate made for another computer is refused'
Check (-not (Get-Service ims -ErrorAction SilentlyContinue)) 'nothing was installed before that refusal'
Set-Content $identity "NOM=$env:COMPUTERNAME`r`nADRESSE=127.0.0.1`r`nEXPIRE=-"
$install = Invoke-Captured { & $launcher }
Check ($install.Output -match "n'est pas chiffr.*ADR-169") 'install.ps1 warned that the drive is not encrypted'
Check ($install.Output -match 'Installation termin') 'install.ps1 reached its end'
$match = [regex]::Match($install.Output, 'Premier administrateur : admin / (\S+)')
Check $match.Success 'the first Administrator password was printed'
$adminPassword = $match.Groups[1].Value
Assert-Healthy 'after the installation'
$serviceXml = Get-Content 'C:\IMS\app\ims-service.xml' -Raw
Check ($serviceXml -notmatch 'IMS_BOOTSTRAP_ADMIN') 'the bootstrap variables were removed from the service file (ADR-040)'
$rule = Get-NetFirewallRule -DisplayName 'IMS V*' -ErrorAction SilentlyContinue
Check ($rule -and $rule.Profile.ToString() -eq 'Private') 'firewall rule on the Private profile only'
$screen = (curl.exe --ssl-no-revoke -sS -D - -o NUL "$base/") -join "`n"
Check ($screen -match '(?im)^content-security-policy:') 'the screens are served with the security headers (ADR-166)'
$redirect = (curl.exe --ssl-no-revoke -sS -o NUL -w '%{http_code} %{redirect_url}' 'http://localhost/') -join ""
Check ($redirect -match '^30[1278] https://localhost') "HTTP redirects to HTTPS ($redirect)"
Check (Test-Path 'C:\IMS\config\migration.cred.xml') 'the migration account is kept for the scripts'

Step '2. A backup through the API, copied to the Drive folder (ADR-167)'
$login = Connect-Api $adminPassword
Check ($login.Status -eq 200 -and $login.Body -match '"mustChangePassword"\s*:\s*true') "first login with the temporary password ($($login.Status))"
$change = Invoke-Api 'POST' '/api/auth/change-password' (@{ currentPassword = $adminPassword; newPassword = $newAdminPassword } | ConvertTo-Json)
Check ($change.Status -eq 204) "password changed ($($change.Status) $($change.Body))"
$login = Connect-Api $newAdminPassword
Check ($login.Status -eq 200) 'login with the new password'
$backup = Invoke-Api 'POST' '/api/backups'
Write-Host "  $($backup.Body)"
$record = $backup.Body | ConvertFrom-Json
Check ($backup.Status -eq 201 -and $record.status -eq 'success') 'the backup succeeded (mysqldump as ims_backup, run by ims-svc)'
Check ($record.copyStatus -eq 'copied') 'the backup was copied to the Drive folder'
$driveCopy = Join-Path $DriveFolder $record.fileName
Check (Test-Path $driveCopy) "the copy is in the Drive folder: $driveCopy"
$restoreSource = Join-Path $env:RUNNER_TEMP $record.fileName
Copy-Item $driveCopy $restoreSource

. (Join-Path $shipped 'ims-common.ps1')
$ErrorActionPreference = 'Stop'
$migration = Get-MigrationCredential
function Invoke-Sql([string] $Sql) {
    $out = Invoke-MySqlTool 'mysql.exe' $migration.UserName $migration.GetNetworkCredential().Password @(
        '--batch', '--skip-column-names', '--database=ims', '--execute', $Sql)
    ($out | Out-String).Trim()
}
function Test-Table([string] $Name) {
    (Invoke-Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'ims' AND table_name = '$Name'") -eq '1'
}
function Get-SchemaVersion {
    Invoke-Sql "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1"
}
$versionA = Get-SchemaVersion
Write-Host "  schema version after the installation: $versionA"

Step '3. update.ps1 with a new version (one more migration)'
$update = Invoke-Captured { & (Join-Path $shipped 'update.ps1') -Jar $JarB }
Check ($update.ExitCode -eq 0 -and $update.Output -match 'Mise . jour termin') 'update.ps1 finished'
Check ((Get-Sha 'C:\IMS\app\ims.jar') -eq (Get-Sha $JarB)) 'the new JAR is in place'
Check ((Get-Sha 'C:\IMS\app\ims.jar.previous') -eq (Get-Sha $JarA)) 'the previous JAR is kept'
Check ((Get-SchemaVersion) -eq '99.001') 'the new migration ran (99.001)'
Check (Test-Table 'ci_update_marker') 'the new version''s table exists'
Check (@(Get-ChildItem 'C:\IMS\backups' -Filter 'avant-mise-a-jour-*.sql').Count -ge 1) 'the database was saved before the update'
Assert-Healthy 'after the update'

Step '4. update.ps1 with a failing migration: the previous version and data come back'
$failed = Invoke-Captured { & (Join-Path $shipped 'update.ps1') -Jar $JarFailing }
Check ($failed.ExitCode -eq 1) "update.ps1 reports the failure (exit $($failed.ExitCode))"
Check ($failed.Output -match 'La version pr.c.dente fonctionne de nouveau') 'it says the previous version works again'
Check ((Get-Sha 'C:\IMS\app\ims.jar') -eq (Get-Sha $JarB)) 'the previous JAR is back'
Check (-not (Test-Table 'ci_partial_change')) 'the failed migration''s partial change is gone'
Check ((Get-SchemaVersion) -eq '99.001') 'the schema is back at 99.001'
Check ((Invoke-Sql "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0") -eq '0') 'no failed migration left in the history'
Assert-Healthy 'after the rollback'
Check ((Connect-Api $newAdminPassword).Status -eq 200) 'the data are those of before the update (login works)'

Step '5. restore.ps1 with the Drive copy of step 2'
Invoke-Sql 'CREATE TABLE ci_after_backup (id INT PRIMARY KEY)' | Out-Null
Check (Test-Table 'ci_after_backup') 'a change made after the backup'
$restore = Invoke-Captured { & (Join-Path $shipped 'restore.ps1') -Backup $restoreSource }
Check ($restore.Output -match 'Restauration termin') 'restore.ps1 finished'
Check (-not (Test-Table 'ci_after_backup')) 'the change made after the backup is gone'
Check ((Get-SchemaVersion) -eq '99.001') 'the backup was migrated to the installed version (99.001)'
Check (@(Get-ChildItem 'C:\IMS\backups' -Filter 'avant-restauration-*.sql').Count -ge 1) 'the data were saved before the restore'
Assert-Healthy 'after the restore'
Check ((Connect-Api $newAdminPassword).Status -eq 200) 'the restored data hold the administrator''s password of before the backup'

Write-Host "`nWindows rehearsal passed: install, backup and Drive copy, update, rollback, restore." -ForegroundColor Green
