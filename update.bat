@echo off
chcp 65001 >nul
cd /d "%~dp0"
git pull
echo 已更新到最新版。如果 README 有說要重新安裝，再執行一次 install.bat。
pause
