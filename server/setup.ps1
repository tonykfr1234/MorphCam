# MorphCam server one-time setup (Windows, NVIDIA GPU).
# Requires: Python 3.10, Git, Visual Studio 2022 Build Tools (C++ workload, for insightface).
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

& py -3.10 --version
if (-not $?) { Write-Error "Python 3.10 not found. Install it from python.org first."; exit 1 }

if (-not (Test-Path .venv)) { & py -3.10 -m venv .venv }
$vpy = ".\.venv\Scripts\python.exe"
& $vpy -m pip install --upgrade pip wheel setuptools

# CUDA 12.1 build of PyTorch (works on RTX 20/30/40 series).
& $vpy -m pip install torch==2.4.1 torchaudio==2.4.1 --index-url https://download.pytorch.org/whl/cu121

# Seed-VC (zero-shot voice conversion).
if (-not (Test-Path third_party\seed-vc)) {
    git clone --depth 1 https://github.com/Plachtaa/seed-vc.git third_party\seed-vc
}
# Install Seed-VC deps without letting it replace our CUDA torch.
Get-Content third_party\seed-vc\requirements.txt |
    Where-Object { $_ -and ($_ -notmatch '^\s*#') -and ($_ -notmatch '^(torch|torchaudio|torchvision)\b') -and ($_ -notmatch '^--') } |
    Set-Content -Encoding utf8 third_party\seedvc-reqs.txt
& $vpy -m pip install -r third_party\seedvc-reqs.txt

& $vpy -m pip install -r requirements.txt
& $vpy download_models.py

Write-Host ""
Write-Host "Setup done. Start the server with:  .\run.ps1" -ForegroundColor Green
