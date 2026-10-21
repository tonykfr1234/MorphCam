"""Offline check of the full-body pipeline: drives it with a video file,
prints fps and writes a side-by-side (input | output) mp4.

    python bench.py --ref person.jpg --video dance.mp4 --frames 150
"""
import argparse
import time
from pathlib import Path

import cv2
import huggingface_hub.file_download as _hf_fd
import numpy as np

_hf_fd.are_symlinks_supported = lambda *a, **k: False
from body_swap import BodySwapper  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("--ref", required=True)
ap.add_argument("--video", required=True)
ap.add_argument("--frames", type=int, default=150)
ap.add_argument("--out", default="bench_out.mp4")
args = ap.parse_args()

eng = BodySwapper()
eng.set_reference(Path(args.ref))
cap = cv2.VideoCapture(args.video)
writer = None
times = []
for i in range(args.frames):
    ok, frame = cap.read()
    if not ok:
        cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
        ok, frame = cap.read()
    t = time.perf_counter()
    out = eng.process(frame)
    times.append(time.perf_counter() - t)
    h = out.shape[0]
    left = cv2.resize(frame, (int(frame.shape[1] * h / frame.shape[0]), h))
    combo = np.hstack([left, out])
    if writer is None:
        writer = cv2.VideoWriter(args.out, cv2.VideoWriter_fourcc(*"mp4v"), 15, combo.shape[1::-1])
    writer.write(combo)
    if i in (10, 60, 120):
        cv2.imwrite(f"bench_frame_{i}.jpg", combo)
writer.release()
steady = times[10:] or times
print(f"frames={len(times)}  steady fps={len(steady) / sum(steady):.2f}  "
      f"p50={np.median(steady) * 1000:.0f}ms  p95={np.percentile(steady, 95) * 1000:.0f}ms")
