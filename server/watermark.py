"""Visible disclosure label burned into every face-swapped frame.

This is intentional and not configurable: swapped video must always be
recognisable as AI-generated.
"""
import cv2
import numpy as np

LABEL = "AI-GENERATED"


def stamp(frame: np.ndarray) -> np.ndarray:
    h, w = frame.shape[:2]
    scale = max(0.5, w / 900)
    thick = max(1, int(scale * 2))
    (tw, th), base = cv2.getTextSize(LABEL, cv2.FONT_HERSHEY_SIMPLEX, scale, thick)
    pad = int(8 * scale)
    x0, y0 = pad, pad
    x1, y1 = x0 + tw + 2 * pad, y0 + th + base + 2 * pad
    overlay = frame.copy()
    cv2.rectangle(overlay, (x0, y0), (x1, y1), (0, 0, 200), -1)
    frame = cv2.addWeighted(overlay, 0.65, frame, 0.35, 0)
    cv2.putText(frame, LABEL, (x0 + pad, y0 + pad + th), cv2.FONT_HERSHEY_SIMPLEX,
                scale, (255, 255, 255), thick, cv2.LINE_AA)
    # Faint diagonal repeat so cropping the corner does not remove it.
    small = scale * 0.6
    for yy in range(int(h * 0.25), h, int(h * 0.35)):
        for xx in range(int(w * 0.15), w, int(w * 0.45)):
            cv2.putText(frame, "AI", (xx, yy), cv2.FONT_HERSHEY_SIMPLEX, small * 2,
                        (255, 255, 255), 1, cv2.LINE_AA)
    return frame
