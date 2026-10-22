@echo off
chcp 65001 >nul
cd /d "%~dp0server"
echo 全身替換模式：第一次啟動要建 TensorRT 引擎，約 30-40 分鐘，請不要關掉另一個視窗。
powershell -NoProfile -ExecutionPolicy Bypass -File run.ps1 -FullBody
pause
