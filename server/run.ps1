Set-Location $PSScriptRoot
# Allow the phone to reach the server through Windows Firewall (asks for admin once).
if (-not (Get-NetFirewallRule -DisplayName "MorphCam 8765" -ErrorAction SilentlyContinue)) {
    Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile -Command "New-NetFirewallRule -DisplayName ''MorphCam 8765'' -Direction Inbound -Protocol TCP -LocalPort 8765 -Action Allow -Profile Private"'
}
.\.venv\Scripts\python.exe main.py
