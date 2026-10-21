"""Voice-cloning consent check.

A voice can only be enrolled when the owner of that voice reads the consent
phrase aloud. We verify two things:
  1. the consent recording actually says the phrase (speech-to-text), and
  2. the consent recording and the reference audio are the same speaker.
"""
import difflib
import re
from pathlib import Path

import librosa
import numpy as np

PHRASE = "我是{name}，我同意使用我的聲音建立AI語音模型，我了解它會被用來即時變聲。"
TEXT_MIN_RATIO = 0.70
SPEAKER_MIN_SIM = 0.35  # ECAPA cosine similarity; same speaker is typically > 0.4


def phrase_for(name: str) -> str:
    return PHRASE.format(name=name.strip())


def _norm(text: str, cc) -> str:
    text = cc.convert(text)
    return re.sub(r"[\W_]+", "", text).lower()


class ConsentVerifier:
    def __init__(self, device: str = "cuda"):
        import opencc
        from faster_whisper import WhisperModel
        from speechbrain.inference.speaker import EncoderClassifier

        compute = "float16" if device == "cuda" else "int8"
        self.asr = WhisperModel("small", device=device, compute_type=compute)
        self.spk = EncoderClassifier.from_hparams(
            source="speechbrain/spkrec-ecapa-voxceleb",
            savedir=str(Path(__file__).parent / "models" / "ecapa"),
            run_opts={"device": device},
        )
        self.cc = opencc.OpenCC("t2s")

    def _embed(self, path: Path) -> np.ndarray:
        import torch

        wav, _ = librosa.load(str(path), sr=16000, mono=True, duration=30)
        with torch.no_grad():
            emb = self.spk.encode_batch(torch.tensor(wav).unsqueeze(0))
        v = emb.squeeze().cpu().numpy()
        return v / (np.linalg.norm(v) + 1e-9)

    def verify(self, name: str, consent_wav: Path, reference: Path) -> dict:
        expected = phrase_for(name)
        segments, _ = self.asr.transcribe(
            str(consent_wav), language="zh", initial_prompt=expected, vad_filter=True
        )
        heard = "".join(s.text for s in segments)
        ratio = difflib.SequenceMatcher(
            None, _norm(expected, self.cc), _norm(heard, self.cc)
        ).ratio()
        sim = float(np.dot(self._embed(consent_wav), self._embed(reference)))
        ok = ratio >= TEXT_MIN_RATIO and sim >= SPEAKER_MIN_SIM
        reason = None
        if ratio < TEXT_MIN_RATIO:
            reason = "同意聲明的內容不符，請完整清楚地念出聲明"
        elif sim < SPEAKER_MIN_SIM:
            reason = "念同意聲明的人和參考聲音不是同一個人"
        return {"ok": ok, "reason": reason, "heard": heard,
                "text_match": round(ratio, 3), "speaker_similarity": round(sim, 3)}
