"""Real-time face swap using InsightFace (buffalo_l detector + inswapper_128)."""
from pathlib import Path

import cv2
import numpy as np

from watermark import stamp


def _providers():
    import onnxruntime as ort

    # onnxruntime-gpu can reuse the CUDA/cuDNN DLLs shipped with torch.
    try:
        import torch  # noqa: F401
        if hasattr(ort, "preload_dlls"):
            ort.preload_dlls()
    except Exception:
        pass
    available = ort.get_available_providers()
    if "CUDAExecutionProvider" in available:
        return ["CUDAExecutionProvider", "CPUExecutionProvider"]
    print("[face] WARNING: CUDA not available, running on CPU (very slow)")
    return ["CPUExecutionProvider"]


class FaceSwapper:
    def __init__(self, swapper_model: Path, det_size: int = 320):
        import insightface
        from insightface.app import FaceAnalysis

        if not swapper_model.exists():
            raise FileNotFoundError(
                f"{swapper_model} not found. Run: python download_models.py"
            )
        providers = _providers()
        self.analyser = FaceAnalysis(
            name="buffalo_l",
            allowed_modules=["detection", "recognition"],
            providers=providers,
        )
        self.analyser.prepare(ctx_id=0, det_size=(det_size, det_size))
        self.swapper = insightface.model_zoo.get_model(str(swapper_model), providers=providers)
        self._sources: dict[str, object] = {}

    def _largest_face(self, img: np.ndarray):
        faces = self.analyser.get(img)
        if not faces:
            return None
        return max(faces, key=lambda f: (f.bbox[2] - f.bbox[0]) * (f.bbox[3] - f.bbox[1]))

    def load_source(self, image_path: Path):
        key = str(image_path)
        if key not in self._sources:
            img = cv2.imread(key)
            if img is None:
                raise ValueError("cannot read image")
            face = self._largest_face(img)
            if face is None:
                raise ValueError("no face found in photo")
            self._sources[key] = face
        return self._sources[key]

    def has_face(self, image_path: Path) -> bool:
        try:
            self.load_source(image_path)
            return True
        except ValueError:
            return False

    def process(self, frame: np.ndarray, source) -> np.ndarray:
        if source is not None:
            for target in self.analyser.get(frame):
                frame = self.swapper.get(frame, target, source, paste_back=True)
            return stamp(frame)
        return frame
