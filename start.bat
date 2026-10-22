@echo off
chcp 65001 >nul
cd /d "%~dp0server"
powershell -NoProfile -ExecutionPolicy Bypass -File run.ps1
pause
