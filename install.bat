@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ============================================================
echo  MorphCam 一鍵安裝（主伺服器 + 全身替換），約 30 分鐘
echo ============================================================
git pull
echo.
echo [1/2] 安裝主伺服器（換臉、變聲）...
powershell -NoProfile -ExecutionPolicy Bypass -File server\setup.ps1
if errorlevel 1 goto fail
echo.
echo [2/2] 安裝全身替換...
powershell -NoProfile -ExecutionPolicy Bypass -File server\fullbody\setup.ps1
if errorlevel 1 goto fail
echo.
echo 安裝完成！之後雙擊 start.bat 或 start-fullbody.bat 啟動。
pause
exit /b 0
:fail
echo.
echo 安裝失敗，請把上面的錯誤訊息貼給 Claude。
pause
exit /b 1
