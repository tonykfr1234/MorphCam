"""Full-body worker. Runs in its own venv (StreamDiffusion pins its own deps)
and listens on localhost only; the main server proxies /ws/body to it."""
import asyncio
import json
import os
import re
import sys
from pathlib import Path

import cv2
import numpy as np
import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect

# Windows without Developer Mode cannot create symlinks, and huggingface_hub's
# detection is unreliable there; copy files into the cache instead.
import huggingface_hub.file_download as _hf_fd  # noqa: E402
_hf_fd.are_symlinks_supported = lambda *a, **k: False

HERE = Path(__file__).parent
FACES = Path(os.environ.get("MORPHCAM_DATA", HERE.parent / "data")) / "faces"
PORT = int(os.environ.get("MORPHCAM_BODY_PORT", "18766"))
ID_RE = re.compile(r"^[0-9a-f]{32}$")

app = FastAPI(title="MorphCam full-body worker")
_engine = None
_lock = asyncio.Lock()


def engine():
    global _engine
    if _engine is None:
        from body_swap import BodySwapper
        print("[body] loading StreamDiffusion (first run downloads ~6 GB) ...")
        _engine = BodySwapper()
        print("[body] ready")
    return _engine


@app.get("/health")
def health():
    return {"ok": True, "loaded": _engine is not None}


@app.websocket("/ws/body")
async def ws_body(ws: WebSocket):
    """Text {"face_id": "..."} picks the reference photo; binary = JPEG frames."""
    await ws.accept()
    try:
        eng = await asyncio.to_thread(engine)
    except Exception as e:
        await ws.send_text(json.dumps({"error": f"full-body engine failed: {e}"}))
        await ws.close()
        return
    try:
        while True:
            msg = await ws.receive()
            if msg["type"] == "websocket.disconnect":
                break
            if msg.get("text"):
                fid = json.loads(msg["text"]).get("face_id")
                path = FACES / f"{fid}.jpg"
                if fid and ID_RE.match(fid) and path.exists():
                    async with _lock:
                        await asyncio.to_thread(eng.set_reference, path)
                    await ws.send_text(json.dumps({"face": fid}))
            elif msg.get("bytes"):
                frame = cv2.imdecode(np.frombuffer(msg["bytes"], np.uint8), cv2.IMREAD_COLOR)
                if frame is None:
                    continue
                async with _lock:
                    out = await asyncio.to_thread(eng.process, frame)
                ok, buf = cv2.imencode(".jpg", out, [cv2.IMWRITE_JPEG_QUALITY, 80])
                if ok:
                    await ws.send_bytes(buf.tobytes())
    except WebSocketDisconnect:
        pass


if __name__ == "__main__":
    os.chdir(HERE)  # engine_dir and relative paths in config.yaml
    sys.path.insert(0, str(HERE))
    # Load before serving: loading pegs the CPU long enough to break
    # websocket keepalives if it happens inside a connection.
    engine()
    uvicorn.run(app, host="127.0.0.1", port=PORT, ws_max_size=8 * 1024 * 1024, ws_ping_interval=None)
