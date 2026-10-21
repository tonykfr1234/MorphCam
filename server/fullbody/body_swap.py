"""Real-time full-body replacement from a single photo.

Per camera frame:
  1. MediaPipe pose of the live person -> OpenPose ControlNet (keeps your motion)
  2. Reference photo -> IP-Adapter (borrows their look/clothes)
  3. StreamDiffusion (SD1.5 + LCM, 2 steps) renders the new person
  4. inswapper pastes the reference face for identity, then the AI watermark
"""
import os
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import cv2
import numpy as np
import torch
from PIL import Image

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE.parent))  # shared watermark.py
from watermark import stamp  # noqa: E402


def _to_pil(bgr: np.ndarray, size: int) -> Image.Image:
    """Center-crop to square and resize, BGR -> RGB PIL."""
    h, w = bgr.shape[:2]
    s = min(h, w)
    y0, x0 = (h - s) // 2, (w - s) // 2
    crop = cv2.resize(bgr[y0:y0 + s, x0:x0 + s], (size, size), interpolation=cv2.INTER_AREA)
    return Image.fromarray(cv2.cvtColor(crop, cv2.COLOR_BGR2RGB))


def _fit_reference(bgr: np.ndarray, size: int) -> Image.Image:
    """Pad (not crop) the reference so the whole body stays in view."""
    h, w = bgr.shape[:2]
    s = max(h, w)
    canvas = np.full((s, s, 3), 255, np.uint8)
    canvas[(s - h) // 2:(s - h) // 2 + h, (s - w) // 2:(s - w) // 2 + w] = bgr
    return Image.fromarray(cv2.cvtColor(cv2.resize(canvas, (size, size)), cv2.COLOR_BGR2RGB))


class BodySwapper:
    def __init__(self, config_path: Path = HERE / "config.yaml", swapper_model: Path | None = None):
        from streamdiffusion import create_wrapper_from_config, load_config

        from streamdiffusion.preprocessing.processors.mediapipe_pose import MediaPipePosePreprocessor

        cfg = load_config(str(config_path))
        self.pose = MediaPipePosePreprocessor(**cfg.pop("pose_params", {}))
        self.pose_pool = ThreadPoolExecutor(max_workers=1)
        self.pending = None  # (pose future, camera image) of the frame waiting for the GPU
        cfg["output_type"] = "pt"
        cfg["engine_dir"] = os.environ.get("MORPHCAM_ENGINE_DIR", str(HERE / "engines" / "sd15"))
        self.size = int(cfg.get("width", 512))
        self.prompt = cfg.get("prompt", "a photo of a person")
        self.wrapper = create_wrapper_from_config(cfg)
        self.base: Image.Image | None = None
        self.ref_face = None
        self.face = None
        swapper_model = swapper_model or HERE.parent / "models" / "inswapper_128_fp16.onnx"
        if swapper_model.exists():
            import insightface
            from insightface.app import FaceAnalysis

            providers = ["CUDAExecutionProvider", "CPUExecutionProvider"]
            self.analyser = FaceAnalysis(name="buffalo_l", allowed_modules=["detection", "recognition"],
                                         providers=providers)
            self.analyser.prepare(ctx_id=0, det_size=(320, 320))
            self.face = insightface.model_zoo.get_model(str(swapper_model), providers=providers)
        else:
            print(f"[body] {swapper_model} missing: identity face paste disabled")

    def set_reference(self, image_path: Path) -> None:
        bgr = cv2.imread(str(image_path))
        if bgr is None:
            raise ValueError("cannot read reference image")
        self.base = _fit_reference(bgr, self.size)
        self.wrapper.update_style_image(self.base)
        # The fork only appends IP-Adapter tokens when prompt embeddings are
        # rebuilt, so re-encode the prompt to pick up the new reference.
        self.wrapper.update_stream_params(prompt_list=[(self.prompt, 1.0)])
        self.ref_face = None
        if self.face is not None:
            faces = self.analyser.get(bgr)
            if faces:
                self.ref_face = max(faces, key=lambda f: (f.bbox[2] - f.bbox[0]) * (f.bbox[3] - f.bbox[1]))

    @torch.inference_mode()
    def process(self, frame_bgr: np.ndarray) -> np.ndarray:
        if self.base is None:
            return frame_bgr
        # Two-stage pipeline: start CPU pose detection for this frame, then
        # render the previous frame on the GPU while it runs. Costs one frame
        # of latency, but throughput becomes max(pose, GPU) instead of the sum.
        cam = _to_pil(frame_bgr, self.size)
        job = (self.pose_pool.submit(self.pose, cam), cam)
        prev, self.pending = self.pending or job, job
        pose_img, cam = prev[0].result(), prev[1]

        # The live frame is also the img2img base: with heavy noise it only
        # contributes layout/lighting, while IP-Adapter supplies the
        # reference's look. (Using the reference photo as base left a ghost
        # copy of that person in the frame.)
        self.wrapper.update_control_image(0, pose_img)
        out = self.wrapper(cam)
        if out.dim() == 4:
            out = out[0]
        rgb = (out.clamp(0, 1) * 255).to(torch.uint8).permute(1, 2, 0).cpu().numpy()
        bgr = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
        if self.ref_face is not None:
            # Detection only: the swapper needs just the target's keypoints, so
            # skipping per-frame recognition saves ~50 ms on an RTX 3060.
            from insightface.app.common import Face

            bboxes, kpss = self.analyser.det_model.detect(bgr, max_num=0, metric="default")
            for bbox, kps in zip(bboxes, kpss if kpss is not None else []):
                target = Face(bbox=bbox[:4], kps=kps, det_score=bbox[4])
                bgr = self.face.get(bgr, target, self.ref_face, paste_back=True)
        return stamp(bgr)
