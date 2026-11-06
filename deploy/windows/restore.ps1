<#
.SYNOPSIS
    Restores a backup into IMS Vétérinaire (docs/operations.md §4; README, Restoring a backup; ADR-165, ADR-167).
.DESCRIPTION
    After a lost or broken laptop: install.ps1 on the replacement Windows PC first, then this script with the newest
    backup from Google Drive (ims-backup-….sql.gz) or from C:\IMS\backups. The current data is saved first, then
    replaced by the backup; the schema is migrated to this version and the application started. Everything entered
    after the backup's time must be entered again from the paper records.
.PARAMETER Backup
    The backup file: a .sql.gz of the backups page or Google Drive, or a .sql saved by update.ps1.
#>
param([Parameter(Mandatory)] [string] $Backup)

. "$PSScriptRoot\ims-common.ps1"
Assert-Administrator
if (-not (Test-Path $Backup)) { throw "Fichier introuvable : $Backup" }
$java = Find-Java
$migration = Get-MigrationCredential
$environment = Get-ServiceEnvironment
$started = Get-Date

Write-Step 'Sauvegarde des données actuelles (au cas où)'
$current = Save-Database $migration 'avant-restauration'
Write-Host "    $current"

Write-Step 'Arrêt de l''application'
Stop-Service $ImsService

Write-Step "Restauration de $Backup"
Restore-Database $migration $Backup

Write-Step 'Migration à la version installée (ADR-151)'
Invoke-Migrate $java $ImsJar $migration $environment

Write-Step 'Démarrage'
Start-Service $ImsService
Wait-Healthy
$minutes = [math]::Round(((Get-Date) - $started).TotalMinutes, 1)
Write-Host "Restauration terminée en $minutes min : vérifiez la page Intégrité du stock et les derniers documents (docs/operations.md §4)." -ForegroundColor Green
