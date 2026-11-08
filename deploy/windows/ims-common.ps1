# Shared by install.ps1, update.ps1 and restore.ps1 (docs/deployment.md, docs/operations.md; ADR-151, ADR-165).
# Dot-sourced: . "$PSScriptRoot\ims-common.ps1". Messages are in French: the owner runs these scripts (ADR-165).
# Written for Windows PowerShell 5.1, which every Windows 10/11 has, and PowerShell 7.

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$script:ImsRoot = 'C:\IMS'
$script:ImsApp = Join-Path $ImsRoot 'app'
$script:ImsBackups = Join-Path $ImsRoot 'backups'
$script:ImsLogs = Join-Path $ImsRoot 'logs'
$script:ImsConfig = Join-Path $ImsRoot 'config'
$script:ImsJar = Join-Path $ImsApp 'ims.jar'
$script:ImsService = 'ims'
$script:ImsSchema = 'ims'
$script:MigrationCredential = Join-Path $ImsConfig 'migration.cred.xml'
# Well-known SIDs, so the rights work on a French Windows too ("Administrateurs", "Système").
$script:Administrators = '*S-1-5-32-544'
$script:LocalSystem = '*S-1-5-18'

function Write-Step([string] $Message) {
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Assert-Administrator {
    $identity = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
    if (-not $identity.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Lancez ce script dans PowerShell ouvert en tant qu''administrateur.'
    }
}

# ADR-169 (supersedes ADR-168's refusal): disk encryption is recommended, not required. The installation reports the
# state of the drives holding the database, the backups and the Drive copy, and goes on either way. Read through WMI,
# not manage-bde, whose output is translated. ProtectionStatus 1 = protection on (BitLocker on Windows Pro, "Device
# encryption" on Windows Home: both are BitLocker volumes).
function Show-DiskEncryption([string[]] $Paths) {
    $drives = $Paths | ForEach-Object { [IO.Path]::GetPathRoot($_).TrimEnd('\').ToUpperInvariant() } | Sort-Object -Unique
    try {
        $volumes = @(Get-CimInstance -Namespace 'root\cimv2\Security\MicrosoftVolumeEncryption' -ClassName Win32_EncryptableVolume)
    } catch {
        $volumes = @()
    }
    foreach ($drive in $drives) {
        $volume = $volumes | Where-Object { $_.DriveLetter -eq $drive } | Select-Object -First 1
        if ($volume -and $volume.ProtectionStatus -eq 1) {
            Write-Host "    Disque $drive chiffré."
        } else {
            Write-Warning ("Le disque $drive n'est pas chiffré : l'installation continue (ADR-169). En cas de vol de " +
                "l'ordinateur, les données seraient lisibles ; BitLocker ou le chiffrement de l'appareil reste conseillé " +
                '(docs/deployment.md §3).')
        }
    }
}

# The application listens on 443 and 80 (ADR-166): a program already holding one (IIS, another web server) would only
# show up at the end as "no answer". PID 4 is Windows itself, for a program that goes through its HTTP service.
function Assert-PortsFree([int[]] $Ports = @(443, 80)) {
    foreach ($port in $Ports) {
        $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($listener) {
            $owner = if ($listener.OwningProcess -eq 4) { 'Windows (service HTTP, par exemple IIS)' } else {
                $process = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
                if ($process) { "$($process.ProcessName) (PID $($listener.OwningProcess))" } else { "PID $($listener.OwningProcess)" }
            }
            throw "Le port $port est déjà utilisé par $owner : arrêtez ou désinstallez ce programme, puis relancez (docs/deployment.md §3)."
        }
    }
}

# Letters and digits only (no quote or backslash to escape in an option file or XML), without bias.
function New-Secret([int] $Length = 24) {
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    $limit = 256 - (256 % $alphabet.Length)
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    $byte = New-Object byte[] 1
    $chars = New-Object System.Text.StringBuilder
    while ($chars.Length -lt $Length) {
        $random.GetBytes($byte)
        if ($byte[0] -lt $limit) {
            [void] $chars.Append($alphabet[$byte[0] % $alphabet.Length])
        }
    }
    $random.Dispose()
    $chars.ToString()
}

# Runs a program and waits; its output goes to files, so a line on its error stream never stops the script.
function Invoke-Program([string] $Program, [string[]] $Arguments, [string] $Log) {
    $process = Start-Process -FilePath $Program -ArgumentList $Arguments -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $Log -RedirectStandardError "$Log.err"
    $process.ExitCode
}

# icacls with a check: a right that was not set must stop the installation.
function Set-Rights([string] $Path, [string[]] $Arguments) {
    & icacls $Path @Arguments | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Droits non appliqués sur $Path (icacls code $LASTEXITCODE)."
    }
}

function Find-Java {
    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) {
        throw 'Java 21 introuvable : installez Eclipse Temurin 21 (JDK) avec l''option "JAVA_HOME" (docs/deployment.md §2).'
    }
    $log = Join-Path $env:TEMP 'ims-java-version.txt'
    [void] (Invoke-Program $java.Source @('-version') $log)
    $version = (Get-Content "$log.err" -TotalCount 1)
    Remove-Item $log, "$log.err" -Force -ErrorAction SilentlyContinue
    if ($version -notmatch '"21[\."]') {
        throw "Java 21 est requis, trouvé : $version"
    }
    $java.Source
}

function Find-MySqlBin {
    $bin = Get-ChildItem 'C:\Program Files\MySQL' -Directory -Filter 'MySQL Server 8.4*' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $bin) {
        throw 'MySQL Server 8.4 introuvable dans C:\Program Files\MySQL (docs/deployment.md §3).'
    }
    Join-Path $bin.FullName 'bin'
}

# Runs mysql.exe or mysqldump.exe with the password in a private option file, never on the command line (as the
# application does for its backups, ADR-144). Extra arguments follow the option file.
function Invoke-MySqlTool([string] $Tool, [string] $User, [string] $Password, [string[]] $Arguments) {
    $options = Join-Path $env:TEMP ("ims-mysql-" + [Guid]::NewGuid().ToString('N') + '.cnf')
    try {
        $quoted = $Password.Replace('\', '\\').Replace('"', '\"')
        # UTF-8 without a byte-order mark: MySQL would not recognise "[client]" after one.
        [IO.File]::WriteAllText($options, "[client]`nuser=$User`npassword=`"$quoted`"`nhost=127.0.0.1`nprotocol=TCP`n",
            (New-Object Text.UTF8Encoding $false))
        & icacls $options /inheritance:r /grant:r "${env:USERNAME}:(R,W,D)" | Out-Null
        & (Join-Path (Find-MySqlBin) $Tool) "--defaults-extra-file=$options" @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "$Tool a échoué (code $LASTEXITCODE)."
        }
    } finally {
        Remove-Item $options -Force -ErrorAction SilentlyContinue
    }
}

function Get-MigrationCredential {
    if (-not (Test-Path $MigrationCredential)) {
        throw "Compte de migration introuvable ($MigrationCredential) : relancez depuis le compte Windows qui a fait l'installation."
    }
    Import-Clixml $MigrationCredential
}

# A full dump with the migration account, before an update or a restore (README, Production release).
function Save-Database([pscredential] $Migration, [string] $Label) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $file = Join-Path $ImsBackups "$Label-$stamp.sql"
    Invoke-MySqlTool 'mysqldump.exe' $Migration.UserName $Migration.GetNetworkCredential().Password @(
        '--single-transaction', '--routines', '--triggers', '--no-tablespaces', '--set-gtid-purged=OFF',
        '--hex-blob', '--default-character-set=utf8mb4', "--result-file=$file", '--databases', $ImsSchema)
    $file
}

# Replaces the schema by a dump: dropped, created empty, imported (README, Restoring a backup). A .sql.gz from the
# backups page or Google Drive is decompressed first.
function Restore-Database([pscredential] $Migration, [string] $Dump) {
    $sql = $Dump
    if ($Dump.EndsWith('.gz')) {
        $sql = Join-Path $env:TEMP ([IO.Path]::GetFileNameWithoutExtension($Dump))
        $in = [IO.File]::OpenRead($Dump)
        try {
            $gzip = New-Object IO.Compression.GZipStream($in, [IO.Compression.CompressionMode]::Decompress)
            $out = [IO.File]::Create($sql)
            try { $gzip.CopyTo($out) } finally { $out.Dispose(); $gzip.Dispose() }
        } finally {
            $in.Dispose()
        }
    }
    try {
        $password = $Migration.GetNetworkCredential().Password
        Invoke-MySqlTool 'mysql.exe' $Migration.UserName $password @(
            '--execute', "DROP DATABASE IF EXISTS $ImsSchema; CREATE DATABASE $ImsSchema CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;")
        Invoke-MySqlTool 'mysql.exe' $Migration.UserName $password @(
            '--default-character-set=utf8mb4', '--execute', "source $($sql.Replace('\', '/'))")
    } finally {
        if ($sql -ne $Dump) { Remove-Item $sql -Force -ErrorAction SilentlyContinue }
    }
}

# ADR-151: the migrate mode of the given JAR, with the migration account for this run only.
function Invoke-Migrate([string] $Java, [string] $Jar, [pscredential] $Migration,
        [System.Collections.IDictionary] $Environment) {
    $saved = @{}
    $run = @{}
    foreach ($key in $Environment.Keys) { $run[$key] = $Environment[$key] }
    $run['SPRING_PROFILES_ACTIVE'] = 'prod,migrate'
    $run['IMS_DB_MIGRATION_USERNAME'] = $Migration.UserName
    $run['IMS_DB_MIGRATION_PASSWORD'] = $Migration.GetNetworkCredential().Password
    foreach ($name in $run.Keys) {
        $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $run[$name], 'Process')
    }
    try {
        $log = Join-Path $ImsLogs ("migrate-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
        $code = Invoke-Program $Java @('-jar', "`"$Jar`"") $log
        $ok = ($code -eq 0) -and (Select-String -Path $log -Pattern 'Migrate mode: schema at version' -Quiet)
        if (-not $ok) {
            throw "La migration a échoué : voir $log"
        }
    } finally {
        foreach ($name in $saved.Keys) {
            [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process')
        }
    }
}

# The service's environment, read back from its WinSW file (the single place it is kept).
function Get-ServiceEnvironment {
    [xml] $xml = Get-Content (Join-Path $ImsApp 'ims-service.xml') -Raw
    $values = @{}
    foreach ($entry in $xml.service.env) {
        $values[$entry.name] = $entry.value
    }
    $values
}

function Wait-Healthy([int] $Seconds = 180) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            # Windows PowerShell 5.1 gives the body as bytes for a type it does not know as text (the actuator's own
            # JSON type): ask for plain JSON, and decode bytes if they come anyway.
            $response = Invoke-WebRequest -Uri 'https://localhost/actuator/health' -UseBasicParsing -TimeoutSec 5 `
                -Headers @{ Accept = 'application/json' }
            $body = if ($response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($response.Content) } else { [string] $response.Content }
            if ($body -match '"UP"') {
                return
            }
        } catch {
            Start-Sleep -Seconds 3
        }
    }
    throw "L'application ne répond pas sur https://localhost après $Seconds s : voir $ImsLogs."
}
