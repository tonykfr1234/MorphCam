# MorphCam server one-time setup (Windows, NVIDIA GPU).
# Requires: any Python 3 with the "py" launcher, Git, Visual Studio 2022 Build
# Tools (C++ workload, for insightface). Clone to a short path such as C:\MorphCam.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# uv fetches a private Python 3.10, so no system-wide 3.10 install is needed.
& py -m pip install --user -q uv; if ($LASTEXITCODE -ne 0) { Write-Host "FAILED: step above" -ForegroundColor Red; exit 1 }
if (-not (Test-Path .venv)) { & py -m uv venv --python 3.10 --seed .venv }
$vpy = ".\.venv\Scripts\python.exe"
& $vpy -m pip install --upgrade pip wheel setuptools; if ($LASTEXITCODE -ne 0) { Write-Host "FAILED: step above" -ForegroundColor Red; exit 1 }

# CUDA 12.1 build of PyTorch (works on RTX 20/30/40 series).
& $vpy -m pip install torch==2.4.1 torchaudio==2.4.1 --index-url https://download.pytorch.org/whl/cu121; if ($LASTEXITCODE -ne 0) { Write-Host "FAILED: step above" -ForegroundColor Red; exit 1 }

# Seed-VC (zero-shot voice conversion).
if (-not (Test-Path third_party\seed-vc)) {
    git clone --depth 1 https://github.com/Plachtaa/seed-vc.git third_party\seed-vc; if ($LASTEXITCODE -ne 0) { exit 1 }
}
# Install Seed-VC deps without letting it replace our CUDA torch.
Get-Content third_party\seed-vc\requirements.txt |
    Where-Object { $_ -and ($_ -notmatch '^\s*#') -and ($_ -notmatch '^(torch|torchaudio|torchvision)\b') -and ($_ -notmatch '^--') } |
    Set-Content -Encoding utf8 third_party\seedvc-reqs.txt
& $vpy -m pip install -r third_party\seedvc-reqs.txt; if ($LASTEXITCODE -ne 0) { Write-Host "FAILED: step above" -ForegroundColor Red; exit 1 }

& $vpy -m pip install -r requirements.txt; if ($LASTEXITCODE -ne 0) { Write-Host "FAILED: step above" -ForegroundColor Red; exit 1 }
& $vpy download_models.py; if ($LASTEXITCODE -ne 0) { Write-Host "FAILED: step above" -ForegroundColor Red; exit 1 }

Write-Host ""
Write-Host "Setup done. Start the server with:  .\run.ps1" -ForegroundColor Green
