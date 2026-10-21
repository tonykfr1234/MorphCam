"""MorphCam PC server: face swap + voice conversion for the Android app.

Run:  python main.py            (listens on 0.0.0.0:8765)
"""
import asyncio
import json
import os
import re
import shutil
import socket
import threading
import uuid
from pathlib import Path

import cv2
import numpy as np
import uvicorn
from fastapi import FastAPI, File, Form, HTTPException, UploadFile, WebSocket, WebSocketDisconnect

ROOT = Path(__file__).parent
DATA = Path(os.environ.get("MORPHCAM_DATA", ROOT / "data"))
FACES = DATA / "faces"
VOICES = DATA / "voices"
MODELS = ROOT / "models"
SEEDVC_DIR = Path(os.environ.get("SEEDVC_DIR", ROOT / "third_party" / "seed-vc"))
PORT = int(os.environ.get("MORPHCAM_PORT", "8765"))
VOICE_BLOCK_S = float(os.environ.get("MORPHCAM_VOICE_BLOCK", "1.0"))
for d in (FACES, VOICES):
    d.mkdir(parents=True, exist_ok=True)

ID_RE = re.compile(r"^[0-9a-f]{32}$")
app = FastAPI(title="MorphCam")

# Models are heavy, so load each one lazily on first use. One GPU lock keeps
# face swap and voice conversion from fighting over VRAM at the same instant.
_engines: dict[str, object] = {}
_load_lock = threading.Lock()
gpu_lock = threading.Lock()


def engine(kind: str):
    with _load_lock:
        if kind not in _engines:
            print(f"[server] loading {kind} engine ...")
            if kind == "face":
                from face_swap import FaceSwapper
                _engines[kind] = FaceSwapper(MODELS / "inswapper_128_fp16.onnx")
            elif kind == "voice":
                from voice import SeedVC
                _engines[kind] = SeedVC(SEEDVC_DIR)
            elif kind == "consent":
                from consent import ConsentVerifier
                _engines[kind] = ConsentVerifier()
            print(f"[server] {kind} engine ready")
        return _engines[kind]


def check_id(i: str) -> str:
    if not ID_RE.match(i or ""):
        raise HTTPException(400, "bad id")
    return i


@app.get("/health")
def health():
    return {"ok": True, "loaded": list(_engines)}


# ---------------------------------------------------------------- faces
@app.post("/faces")
async def upload_face(file: UploadFile = File(...)):
    data = await file.read()
    img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise HTTPException(400, "不是有效的圖片")
    fid = uuid.uuid4().hex
    path = FACES / f"{fid}.jpg"
    cv2.imwrite(str(path), img)
    eng = await asyncio.to_thread(engine, "face")
    if not await asyncio.to_thread(eng.has_face, path):
        path.unlink(missing_ok=True)
        raise HTTPException(422, "照片裡找不到人臉")
    return {"id": fid}


@app.get("/faces")
def list_faces():
    return [p.stem for p in sorted(FACES.glob("*.jpg"))]


@app.websocket("/ws/video")
async def ws_video(ws: WebSocket):
    """Client sends JPEG frames (binary) and {"face_id": "..."} (text)."""
    await ws.accept()
    try:
        eng = await asyncio.to_thread(engine, "face")
    except Exception as e:
        await ws.send_text(json.dumps({"error": str(e)}))
        await ws.close()
        return
    source = None
    try:
        while True:
            msg = await ws.receive()
            if msg["type"] == "websocket.disconnect":
                break
            if msg.get("text"):
                fid = json.loads(msg["text"]).get("face_id")
                if fid and ID_RE.match(fid) and (FACES / f"{fid}.jpg").exists():
                    source = await asyncio.to_thread(eng.load_source, FACES / f"{fid}.jpg")
                    await ws.send_text(json.dumps({"face": fid}))
                else:
                    source = None
            elif msg.get("bytes"):
                frame = cv2.imdecode(np.frombuffer(msg["bytes"], np.uint8), cv2.IMREAD_COLOR)
                if frame is None:
                    continue

                def run():
                    with gpu_lock:
                        return eng.process(frame, source)

                out = await asyncio.to_thread(run)
                ok, buf = cv2.imencode(".jpg", out, [cv2.IMWRITE_JPEG_QUALITY, 75])
                if ok:
                    await ws.send_bytes(buf.tobytes())
    except WebSocketDisconnect:
        pass


# ---------------------------------------------------------------- voices
@app.get("/consent_phrase")
def consent_phrase(name: str):
    from consent import phrase_for
    return {"phrase": phrase_for(name)}


@app.post("/voices")
async def enroll_voice(
    name: str = Form(...),
    consent: UploadFile = File(...),
    reference: UploadFile = File(...),
):
    from voice import prepare_reference

    name = name.strip()[:40]
    if not name:
        raise HTTPException(400, "請輸入聲音主人的名字")
    vid = uuid.uuid4().hex
    vdir = VOICES / vid
    vdir.mkdir()
    try:
        consent_path = vdir / "consent.wav"
        consent_path.write_bytes(await consent.read())
        raw_ref = vdir / ("reference_raw" + Path(reference.filename or "").suffix[:6])
        raw_ref.write_bytes(await reference.read())
        ref = vdir / "ref.wav"
        await asyncio.to_thread(prepare_reference, raw_ref, ref)

        verifier = await asyncio.to_thread(engine, "consent")

        def run():
            with gpu_lock:
                return verifier.verify(name, consent_path, ref)

        result = await asyncio.to_thread(run)
        if not result["ok"]:
            raise HTTPException(403, result)
        raw_ref.unlink(missing_ok=True)
        (vdir / "meta.json").write_text(
            json.dumps({"name": name, "consent": result}, ensure_ascii=False), "utf-8"
        )
        return {"id": vid, "name": name, "check": result}
    except HTTPException:
        shutil.rmtree(vdir, ignore_errors=True)
        raise
    except ValueError as e:
        shutil.rmtree(vdir, ignore_errors=True)
        raise HTTPException(422, str(e))
    except Exception:
        shutil.rmtree(vdir, ignore_errors=True)
        raise


@app.get("/voices")
def list_voices():
    out = []
    for meta in sorted(VOICES.glob("*/meta.json")):
        out.append({"id": meta.parent.name, "name": json.loads(meta.read_text("utf-8"))["name"]})
    return out


@app.delete("/voices/{vid}")
def delete_voice(vid: str):
    shutil.rmtree(VOICES / check_id(vid), ignore_errors=True)
    return {"ok": True}


@app.websocket("/ws/audio")
async def ws_audio(ws: WebSocket, voice_id: str):
    """Client streams 16 kHz mono PCM16 (binary); gets converted PCM16 back."""
    from voice import SR, StreamSession

    await ws.accept()
    ref = VOICES / voice_id / "ref.wav"
    if not ID_RE.match(voice_id) or not (VOICES / voice_id / "meta.json").exists():
        await ws.send_text(json.dumps({"error": "找不到這個聲音，請重新建立"}))
        await ws.close()
        return
    try:
        eng = await asyncio.to_thread(engine, "voice")
    except Exception as e:
        await ws.send_text(json.dumps({"error": str(e)}))
        await ws.close()
        return

    session = StreamSession(eng, ref)
    block_len = int(VOICE_BLOCK_S * SR)
    queue: asyncio.Queue = asyncio.Queue()

    async def worker():
        while True:
            block = await queue.get()
            if block is None:
                return

            def run():
                with gpu_lock:
                    return session.convert(block)

            out = await asyncio.to_thread(run)
            pcm = (np.clip(out, -1, 1) * 32767).astype("<i2").tobytes()
            await ws.send_bytes(pcm)

    task = asyncio.create_task(worker())
    buf = np.zeros(0, dtype=np.float32)
    try:
        while True:
            data = await ws.receive_bytes()
            samples = np.frombuffer(data, dtype="<i2").astype(np.float32) / 32768
            buf = np.concatenate([buf, samples])
            while len(buf) >= block_len:
                block, buf = buf[:block_len], buf[block_len:]
                if queue.qsize() > 2:  # GPU is falling behind; drop to keep latency bounded
                    queue.get_nowait()
                queue.put_nowait(block)
    except WebSocketDisconnect:
        pass
    finally:
        queue.put_nowait(None)
        task.cancel()
        shutil.rmtree(session.tmp, ignore_errors=True)


def lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


if __name__ == "__main__":
    print("=" * 60)
    print(f" MorphCam server  ->  在手機 App 輸入:  http://{lan_ip()}:{PORT}")
    print("=" * 60)
    uvicorn.run(app, host="0.0.0.0", port=PORT, ws_max_size=8 * 1024 * 1024)
