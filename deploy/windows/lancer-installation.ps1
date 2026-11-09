<#
.SYNOPSIS
    Launches install.ps1 from the installer folder (docs/deployment.md §4), started by INSTALLER.cmd as administrator.
.DESCRIPTION
    Finds the JAR, server.p12, ims-ca.crt and WinSW-x64.exe next to the scripts folder, checks the certificate was
    made for this laptop (certificat.txt, deploy/bundle/make-certificate.sh), proposes the Google Drive folder of the
    current Windows account, then runs install.ps1 with them. It changes nothing itself.
#>
$ErrorActionPreference = 'Stop'
$bundle = Split-Path $PSScriptRoot -Parent

function Find-BundleFile([string] $pattern, [string] $what) {
    $found = @(Get-ChildItem -Path $bundle -Filter $pattern -File)
    if ($found.Count -ne 1) {
        throw "$what introuvable (ou en plusieurs exemplaires) dans $bundle : reprenez le dossier d'installation complet."
    }
    return $found[0].FullName
}

Write-Host "Installation d'IMS Vétérinaire depuis $bundle" -ForegroundColor Cyan
Write-Host "Compte Windows : $env:USERNAME (celui du propriétaire, qui fera aussi les mises à jour)"
Get-ChildItem -Path $bundle -Recurse -File | Unblock-File

$jar = Find-BundleFile 'ims-*.jar' 'Le fichier de l''application (ims-....jar)'
$ca = Find-BundleFile 'ims-ca.crt' 'Le certificat de l''autorité (ims-ca.crt)'
$winsw = Find-BundleFile 'WinSW-x64.exe' 'WinSW-x64.exe'
$keystore = Join-Path $bundle 'server.p12'
$identity = Join-Path $bundle 'certificat.txt'
if (-not (Test-Path $keystore) -or -not (Test-Path $identity)) {
    throw 'server.p12 manque : le développeur doit le créer avec le nom et l''adresse fixe de ce portable (LISEZ-MOI.txt, étape 1).'
}

$certificate = @{}
foreach ($line in Get-Content $identity) {
    $parts = $line -split '=', 2
    if ($parts.Count -eq 2) { $certificate[$parts[0]] = $parts[1] }
}
if ($certificate['NOM'] -ne $env:COMPUTERNAME) {
    throw "Le certificat est fait pour l'ordinateur $($certificate['NOM']), celui-ci s'appelle $env:COMPUTERNAME : demandez au développeur un server.p12 à ce nom."
}
if (-not (Get-NetIPAddress -IPAddress $certificate['ADRESSE'] -ErrorAction SilentlyContinue)) {
    Write-Warning "Ce portable n'a pas encore l'adresse $($certificate['ADRESSE']) du certificat : réservez-la pour lui dans la box (LISEZ-MOI.txt), sinon les autres appareils ne le trouveront pas à cette adresse. L'installation continue."
}

$drive = @("$env:USERPROFILE\Mon Drive", "$env:USERPROFILE\My Drive") | Where-Object { Test-Path $_ } | Select-Object -First 1
$proposed = if ($drive) { Join-Path $drive 'IMS-sauvegardes' } else { '' }
Write-Host ''
Write-Host 'Dossier Google Drive qui reçoit une copie de chaque sauvegarde (Google Drive en mode "Mettre en miroir").'
if ($proposed) { Write-Host "Proposé : $proposed" }
$answer = Read-Host 'Entrée pour accepter, ou tapez un autre dossier'
$driveFolder = if ([string]::IsNullOrWhiteSpace($answer)) { $proposed } else { $answer.Trim().Trim('"') }
if ([string]::IsNullOrWhiteSpace($driveFolder)) {
    throw 'Aucun dossier Google Drive : installez Google Drive pour ordinateur en mode "Mettre en miroir" (LISEZ-MOI.txt, étape 2), puis relancez.'
}
if (-not (Test-Path (Split-Path $driveFolder -Parent))) {
    throw "Le dossier $(Split-Path $driveFolder -Parent) n'existe pas : vérifiez Google Drive (mode ""Mettre en miroir"")."
}
$psDrive = Get-PSDrive -Name $driveFolder.Substring(0, 1) -ErrorAction SilentlyContinue
if ($psDrive -and $psDrive.Description -match 'Google') {
    throw 'Ce dossier est sur le lecteur virtuel de Google Drive (mode "Streaming") : passez Google Drive en mode "Mettre en miroir" et choisissez le dossier sous C:\Users\...'
}

& "$PSScriptRoot\install.ps1" -Jar $jar -Keystore $keystore -CaCertificate $ca -WinSW $winsw -DriveFolder $driveFolder
