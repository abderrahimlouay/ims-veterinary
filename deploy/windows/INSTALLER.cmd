@echo off
rem IMS Veterinaire: double-cliquez pour installer (docs/deployment.md section 4).
rem Ouvre PowerShell en administrateur et lance scripts\lancer-installation.ps1 ; la fenetre reste ouverte a la fin.
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile','-NoExit','-ExecutionPolicy','Bypass','-File','\"%~dp0scripts\lancer-installation.ps1\"'"
