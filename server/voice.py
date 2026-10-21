"""Streaming voice conversion on top of Seed-VC (zero-shot, no training needed).

Mic audio arrives as 16 kHz mono blocks. Each block is converted together with
a short slice of the previous input for context, then the context part is cut
off and the seam is cross-faded.
"""
import os
import sys
import tempfile
from pathlib import Path

import librosa
import numpy as np
import soundfile as sf

SR = 16000
CONTEXT_S = 0.4
FADE_S = 0.03
SILENCE_RMS = 0.006


class SeedVC:
    def __init__(self, seedvc_dir: Path, diffusion_steps: int = 6):
        if not (seedvc_dir / "seed_vc_wrapper.py").exists():
            raise FileNotFoundError(
                f"Seed-VC not found at {seedvc_dir}. Run setup.ps1 or set SEEDVC_DIR."
            )
        sys.path.insert(0, str(seedvc_dir))
        cwd = os.getcwd()
        os.chdir(seedvc_dir)  # Seed-VC resolves configs/checkpoints relative to its repo
        try:
            from seed_vc_wrapper import SeedVCWrapper
            self.model = SeedVCWrapper()
        finally:
            os.chdir(cwd)
        self.steps = diffusion_steps
        self.out_sr = 22050

    def convert_file(self, src: Path, ref: Path) -> np.ndarray:
        gen = self.model.convert_voice(
            str(src), str(ref), diffusion_steps=self.steps, length_adjust=1.0,
            inference_cfg_rate=0.7, f0_condition=False, auto_f0_adjust=False,
            pitch_shift=0, stream_output=False,
        )
        # With stream_output=False the wrapper returns the audio as the
        # generator's return value.
        try:
            while True:
                next(gen)
        except StopIteration as stop:
            audio = stop.value
        audio = np.asarray(audio, dtype=np.float32).squeeze()
        return librosa.resample(audio, orig_sr=self.out_sr, target_sr=SR)


class StreamSession:
    """Per-connection state for chunked real-time conversion."""

    def __init__(self, engine: SeedVC, reference: Path):
        self.engine = engine
        self.reference = reference
        self.prev_in = np.zeros(0, dtype=np.float32)
        self.prev_tail = np.zeros(0, dtype=np.float32)
        self.tmp = Path(tempfile.mkdtemp(prefix="morphcam_"))

    def convert(self, block: np.ndarray) -> np.ndarray:
        ctx = self.prev_in[-int(CONTEXT_S * SR):]
        self.prev_in = block
        if np.sqrt(np.mean(block ** 2)) < SILENCE_RMS:
            self.prev_tail = np.zeros(0, dtype=np.float32)
            return np.zeros_like(block)

        src = self.tmp / "chunk.wav"
        sf.write(src, np.concatenate([ctx, block]), SR)
        out = self.engine.convert_file(src, self.reference)

        # Drop the context portion, keep exactly len(block) samples.
        out = out[len(ctx):]
        out = np.pad(out, (0, max(0, len(block) - len(out))))[: len(block)]

        fade = min(int(FADE_S * SR), len(self.prev_tail), len(out))
        if fade:
            ramp = np.linspace(0, 1, fade, dtype=np.float32)
            out[:fade] = out[:fade] * ramp + self.prev_tail[:fade] * (1 - ramp)
        self.prev_tail = out[-int(FADE_S * SR):].copy()
        return out.astype(np.float32)


def prepare_reference(src: Path, dst: Path) -> None:
    """Normalise any uploaded audio to a 22.05 kHz mono wav, max 25 s."""
    wav, _ = librosa.load(str(src), sr=22050, mono=True, duration=25)
    wav, _ = librosa.effects.trim(wav, top_db=35)
    if len(wav) < 22050 * 3:
        raise ValueError("參考聲音太短，至少需要 3 秒清楚的人聲")
    sf.write(dst, wav, 22050)
