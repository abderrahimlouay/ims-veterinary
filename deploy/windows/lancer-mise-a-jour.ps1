<#
.SYNOPSIS
    Launches update.ps1 from a new installer folder (docs/operations.md §2), started by METTRE-A-JOUR.cmd as
    administrator.
.DESCRIPTION
    Finds the one JAR next to the scripts folder, says which version it is (VERSION.txt), refuses a JAR identical to
    the installed one, asks the owner to type OUI (a double-click by mistake during opening hours must not stop the
    application), then runs update.ps1 with it and passes its exit code on. It changes nothing itself.
#>
. "$PSScriptRoot\ims-common.ps1"
Assert-Administrator
$bundle = Split-Path $PSScriptRoot -Parent

Write-Host "Mise à jour d'IMS Vétérinaire depuis $bundle" -ForegroundColor Cyan
Write-Host "Compte Windows : $env:USERNAME (celui qui a fait l'installation)"
Get-ChildItem -Path $bundle -Recurse -File | Unblock-File

$found = @(Get-ChildItem -Path $bundle -Filter 'ims-*.jar' -File)
if ($found.Count -ne 1) {
    throw "Le fichier de l'application (ims-....jar) est introuvable (ou en plusieurs exemplaires) dans $bundle : reprenez le dossier complet envoyé par le développeur."
}
$jar = $found[0].FullName
if (-not (Test-Path $ImsJar)) {
    throw "IMS Vétérinaire n'est pas installé sur cet ordinateur ($ImsJar absent) : utilisez INSTALLER.cmd."
}
$version = Join-Path $bundle 'VERSION.txt'
if (Test-Path $version) { Write-Host "Nouvelle version : $((Get-Content $version -Raw).Trim())" }
Write-Host "Fichier : $(Split-Path $jar -Leaf)"
if ((Get-FileHash $jar -Algorithm SHA256).Hash -eq (Get-FileHash $ImsJar -Algorithm SHA256).Hash) {
    Write-Host 'Cette version est déjà installée : rien à faire.' -ForegroundColor Green
    exit 0
}

Write-Host ''
Write-Host 'L''application va s''arrêter quelques minutes : tous les appareils doivent être déconnectés.' -ForegroundColor Yellow
$answer = Read-Host 'Tapez OUI pour lancer la mise à jour (ou fermez cette fenêtre)'
if ($answer.Trim() -ne 'OUI') {
    Write-Host 'Mise à jour annulée : rien n''a changé.'
    exit 0
}

$global:LASTEXITCODE = 0
& (Join-Path $PSScriptRoot 'update.ps1') -Jar $jar
exit $LASTEXITCODE
