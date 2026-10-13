"""Notes the callee's gender from their voice, the way a telephone interviewer does, so the survey does
not have to ask.

Model: prithivMLmods/Common-Voice-Gender-Detection (wav2vec2-base, Apache-2.0), int8 ONNX export, run with
onnxruntime. Download it with setup_models.py. On 21 test voices passed through a simulated phone line
(8 kHz G.711) it got 20 right with confidence >= 0.94; the only miss had confidence 0.68. Below
MIN_CONFIDENCE the result is treated as unknown and the question is asked instead.

Pitch-based estimation was tried first and does not work on phone audio: the line cuts the frequencies
that carry the pitch, and male voices were read as female (8 of 21 right).
"""
from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass
from pathlib import Path

import numpy as np

MODEL_PATH = Path(__file__).resolve().parent / "models" / "common-voice-gender" / "model_int8.onnx"
SAMPLE_RATE = 16000
MIN_CONFIDENCE = 0.85
MIN_SPEECH_SECONDS = 1.0
MAX_SPEECH_SECONDS = 3.0
CHUNK_SECONDS = 0.02


@dataclass
class GenderResult:
    label: str | None  # "erkek", "kadın", or None when not confident
    confidence: float
    speech_seconds: float
    inference_ms: int


def load_model():
    import onnxruntime

    return onnxruntime.InferenceSession(str(MODEL_PATH), providers=["CPUExecutionProvider"])


class VoiceGenderDetector:
    """Collects the callee's speech (loud chunks only) from the audio sent to speech recognition."""

    def __init__(self, session) -> None:
        self._session = session
        self._resampler = None
        self._input_rate: int | None = None
        self._pending = np.zeros(0, dtype=np.float32)
        self._speech: list[np.ndarray] = []
        self._speech_samples = 0
        self._peak_rms = 0.0
        self._task: asyncio.Task | None = None
        self.result: GenderResult | None = None

    @property
    def speech_seconds(self) -> float:
        return self._speech_samples / SAMPLE_RATE

    def push_frame(self, frame) -> None:
        if self._speech_samples >= MAX_SPEECH_SECONDS * SAMPLE_RATE:
            return
        from livekit import rtc

        if frame.sample_rate != SAMPLE_RATE:
            if self._resampler is None or self._input_rate != frame.sample_rate:
                self._resampler = rtc.AudioResampler(frame.sample_rate, SAMPLE_RATE, num_channels=frame.num_channels)
                self._input_rate = frame.sample_rate
            frames = self._resampler.push(frame)
        else:
            frames = [frame]
        for resampled in frames:
            samples = np.frombuffer(resampled.data, dtype=np.int16).astype(np.float32) / 32768.0
            if resampled.num_channels > 1:
                samples = samples.reshape(-1, resampled.num_channels)[:, 0]
            self._add(samples)

    def _add(self, samples: np.ndarray) -> None:
        self._pending = np.concatenate([self._pending, samples])
        chunk = int(SAMPLE_RATE * CHUNK_SECONDS)
        while len(self._pending) >= chunk:
            piece, self._pending = self._pending[:chunk], self._pending[chunk:]
            rms = float(np.sqrt(np.mean(piece ** 2)))
            self._peak_rms = max(self._peak_rms, rms)
            # Keep speech, drop silence and line noise.
            if rms > max(0.01, 0.15 * self._peak_rms):
                self._speech.append(piece)
                self._speech_samples += len(piece)

    def start(self) -> None:
        """Classifies the speech collected so far in the background (once enough is available)."""
        if self._task is None and self.result is None and self.speech_seconds >= MIN_SPEECH_SECONDS:
            audio = np.concatenate(self._speech)[: int(MAX_SPEECH_SECONDS * SAMPLE_RATE)]
            self._task = asyncio.create_task(asyncio.to_thread(self._classify, audio))

    async def wait(self, timeout: float) -> GenderResult | None:
        self.start()
        if self._task is not None and self.result is None:
            try:
                self.result = await asyncio.wait_for(asyncio.shield(self._task), timeout)
            except asyncio.TimeoutError:
                return None
        return self.result

    def _classify(self, audio: np.ndarray) -> GenderResult:
        started = time.time()
        normalized = (audio - audio.mean()) / (audio.std() + 1e-7)
        logits = self._session.run(None, {"input_values": normalized[None, :].astype(np.float32)})[0][0]
        probabilities = np.exp(logits - logits.max())
        probabilities /= probabilities.sum()
        label, confidence = ("kadın", float(probabilities[0])) if probabilities[0] > probabilities[1] else ("erkek", float(probabilities[1]))
        return GenderResult(
            label if confidence >= MIN_CONFIDENCE else None,
            round(confidence, 3),
            round(len(audio) / SAMPLE_RATE, 2),
            round((time.time() - started) * 1000),
        )
