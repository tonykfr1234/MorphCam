"""Download the face-swap model. InsightFace's buffalo_l and Seed-VC's
checkpoints download themselves automatically on first run."""
import sys
import urllib.request
from pathlib import Path

MODELS = Path(__file__).parent / "models"
FILES = {
    "inswapper_128_fp16.onnx":
        "https://huggingface.co/hacksider/deep-live-cam/resolve/main/inswapper_128_fp16.onnx",
}


def main():
    MODELS.mkdir(exist_ok=True)
    for name, url in FILES.items():
        dst = MODELS / name
        if dst.exists() and dst.stat().st_size > 1_000_000:
            print(f"[ok] {name}")
            continue
        print(f"[download] {name} <- {url}")
        tmp = dst.with_suffix(".part")

        def hook(blocks, bs, total):
            if total > 0:
                sys.stdout.write(f"\r  {min(100, blocks * bs * 100 // total)}%")
                sys.stdout.flush()

        urllib.request.urlretrieve(url, tmp, hook)
        tmp.rename(dst)
        print()


if __name__ == "__main__":
    main()
