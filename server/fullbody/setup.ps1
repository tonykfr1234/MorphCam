# Full-body replacement worker: one-time setup (Windows, NVIDIA GPU with 12 GB VRAM).
# Separate venv because StreamDiffusion pins its own torch/diffusers/transformers.
# Requires: Git, Visual Studio 2022 Build Tools (C++), and the repo cloned to a
# SHORT path such as C:\MorphCam (torch DLL paths break past Windows' 260-char limit).
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# uv fetches a private Python 3.10, so no system-wide install is needed.
& py -m pip install --user -q uv
& py -m uv venv --python 3.10 .venv
$vpy = (Resolve-Path ".\.venv\Scripts\python.exe").Path

& py -m uv pip install --python $vpy torch==2.7.0 torchvision==0.22.0 --index-url https://download.pytorch.org/whl/cu128
# Commit tested on an RTX 3060 12GB (2026-10-22).
& py -m uv pip install --python $vpy "streamdiffusion[tensorrt,controlnet,ipadapter] @ git+https://github.com/daydreamlive/StreamDiffusion.git@4c90d9e437aa28cca7cae1acfab1e52157261939" fastapi "uvicorn[standard]" python-multipart "peft==0.17.1"
& py -m uv pip install --python $vpy xformers==0.0.30 --index-url https://download.pytorch.org/whl/cu128
& $vpy -m streamdiffusion.tools.install-tensorrt

# Shared face-swap model (also used by the main server).
& $vpy ..\download_models.py

Write-Host ""
Write-Host "Full-body setup done. Start with:  ..\run.ps1 -FullBody" -ForegroundColor Green
Write-Host "The first start builds TensorRT engines (about 30-40 minutes on an RTX 3060)." -ForegroundColor Yellow
