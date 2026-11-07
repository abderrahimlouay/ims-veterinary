<#
.SYNOPSIS
    Installs a new version of IMS Vétérinaire (docs/operations.md §2; ADR-151, ADR-165).
.DESCRIPTION
    For the owner, outside opening hours, in PowerShell opened as administrator, from the Windows account that ran
    install.ps1. Saves the database, stops the application, puts the new JAR in place (the previous one kept),
    migrates, starts and checks it answers. If the migration or the start fails, it puts the database and the previous
    version back by itself and says so: then call the developer.
.PARAMETER Jar
    The new release JAR received from the developer.
#>
param([Parameter(Mandatory)] [string] $Jar)

. "$PSScriptRoot\ims-common.ps1"
Assert-Administrator
if (-not (Test-Path $Jar)) { throw "Fichier introuvable : $Jar" }
$java = Find-Java
$migration = Get-MigrationCredential
$environment = Get-ServiceEnvironment
$previous = Join-Path $ImsApp 'ims.jar.previous'

Write-Step 'Sauvegarde de la base avant la mise à jour'
$dump = Save-Database $migration 'avant-mise-a-jour'
Write-Host "    $dump"

Write-Step 'Arrêt de l''application'
Stop-Service $ImsService
Copy-Item $ImsJar $previous -Force
Copy-Item $Jar $ImsJar -Force

try {
    Write-Step 'Migration (ADR-151)'
    Invoke-Migrate $java $ImsJar $migration $environment
    Write-Step 'Démarrage'
    Start-Service $ImsService
    Wait-Healthy
} catch {
    Write-Host "ÉCHEC : $($_.Exception.Message)" -ForegroundColor Red
    Write-Step 'Retour à la version précédente et à la base sauvegardée'
    Stop-Service $ImsService -ErrorAction SilentlyContinue
    Copy-Item $previous $ImsJar -Force
    Restore-Database $migration $dump
    Invoke-Migrate $java $ImsJar $migration $environment
    Start-Service $ImsService
    Wait-Healthy
    Write-Host 'La version précédente fonctionne de nouveau, avec les données d''avant la mise à jour. Appelez le développeur.' -ForegroundColor Yellow
    exit 1
}
Write-Host 'Mise à jour terminée : https://localhost' -ForegroundColor Green
