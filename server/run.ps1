# Start the MorphCam server.
#   .\run.ps1            clothes (phone) + face swap + voice
#   .\run.ps1 -FullBody  also starts the full-body worker. It uses ~11 GB of VRAM,
#                        so on a 12 GB card don't use face swap / voice in the same run.
param([switch]$FullBody)
Set-Location $PSScriptRoot

# Allow the phone to reach the server through Windows Firewall (asks for admin once).
if (-not (Get-NetFirewallRule -DisplayName "MorphCam 18765" -ErrorAction SilentlyContinue)) {
    Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile -Command "New-NetFirewallRule -DisplayName ''MorphCam 18765'' -Direction Inbound -Protocol TCP -LocalPort 18765 -Action Allow -Profile Private"'
}

if ($FullBody) {
    if (-not (Test-Path .\fullbody\.venv\Scripts\python.exe)) {
        Write-Error "Full-body worker is not installed. Run fullbody\setup.ps1 first."
        exit 1
    }
    # Own window so its (long, first-run) engine build log stays visible.
    Start-Process -FilePath .\fullbody\.venv\Scripts\python.exe -ArgumentList "worker.py" -WorkingDirectory .\fullbody
}
.\.venv\Scripts\python.exe main.py
