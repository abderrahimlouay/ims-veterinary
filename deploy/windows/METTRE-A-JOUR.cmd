@echo off
rem IMS Veterinaire: double-cliquez pour installer une nouvelle version (docs/operations.md section 2).
rem Ouvre PowerShell en administrateur et lance scripts\lancer-mise-a-jour.ps1 ; la fenetre reste ouverte a la fin.
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile','-NoExit','-ExecutionPolicy','Bypass','-File','\"%~dp0scripts\lancer-mise-a-jour.ps1\"'"
