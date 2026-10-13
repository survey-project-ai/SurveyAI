"""Notes the callee's gender from their voice, the way a telephone interviewer does; the survey never asks.

Each callee turn with enough speech gets a score (the model's probability that the voice is male). When
the call ends, the scores are averaged, weighted by how much speech each turn had, and a gender is only
recorded when that average is at least DECISION_THRESHOLD one way; otherwise it stays unknown.

A turn's speech is kept with its natural pauses (from 100 ms before to 500 ms after loud audio) and only
long silences are cut. Keeping just the loud 20 ms chunks chopped the speech up: on 30 sample voices
(5 voices x 6 TTS models, 2 s turns after 3 s of line noise) that got 12 right, 4 wrong and 14 unsure;
keeping the pauses got 28 right, 0 wrong, 2 unsure.

Model: prithivMLmods/Common-Voice-Gender-Detection (wav2vec2-base, Apache-2.0), int8 ONNX export, run with
onnxruntime. Download it with setup_models.py. On 21 test voices passed through a simulated phone line
(8 kHz G.711) it got 20 right with confidence >= 0.94; the only miss had confidence 0.68.

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
DECISION_THRESHOLD = 0.8
MIN_SEGMENT_SECONDS = 0.8  # shorter turns ("Evet") carry too little voice to score
MAX_SEGMENT_SECONDS = 4.0  # longer turns are scored on their first seconds, which keeps scoring fast
CHUNK_SECONDS = 0.02
SPEECH_FLOOR = 0.003  # RMS below this is line noise whatever the turn's level
SPEECH_RELATIVE = 0.15  # and so is anything this far under the turn's loudest chunk
KEEP_BEFORE_CHUNKS = 5  # 100 ms
KEEP_AFTER_CHUNKS = 25  # 500 ms
MAX_TURN_SECONDS = 30.0  # audio kept between two turns, speech or not


@dataclass
class SegmentScore:
    male_probability: float
    speech_seconds: float
    inference_ms: int


@dataclass
class GenderResult:
    label: str | None  # "erkek", "kadın", or None when not confident
    confidence: float  # speech-weighted average probability of the more likely label
    speech_seconds: float
    segments: int


def load_model():
    import onnxruntime

    return onnxruntime.InferenceSession(str(MODEL_PATH), providers=["CPUExecutionProvider"])


def combine(scores: list[SegmentScore]) -> GenderResult:
    total = sum(score.speech_seconds for score in scores)
    if not total:
        return GenderResult(None, 0.0, 0.0, 0)
    male = sum(score.male_probability * score.speech_seconds for score in scores) / total
    label, confidence = ("erkek", male) if male >= 0.5 else ("kadın", 1 - male)
    return GenderResult(label if confidence >= DECISION_THRESHOLD else None, round(confidence, 3),
                        round(total, 2), len(scores))


def speech_regions(chunks: list[np.ndarray]) -> np.ndarray:
    """The speech in a turn with its short pauses; long silences removed."""
    if not chunks:
        return np.zeros(0, dtype=np.float32)
    rms = np.array([np.sqrt(np.mean(chunk ** 2)) for chunk in chunks])
    loud = rms > max(SPEECH_FLOOR, SPEECH_RELATIVE * rms.max())
    keep = np.zeros(len(chunks), dtype=bool)
    for index in np.flatnonzero(loud):
        keep[max(0, index - KEEP_BEFORE_CHUNKS): index + KEEP_AFTER_CHUNKS + 1] = True
    kept = [chunk for chunk, wanted in zip(chunks, keep) if wanted]
    return np.concatenate(kept) if kept else np.zeros(0, dtype=np.float32)


class VoiceGenderDetector:
    """Collects the callee's speech (loud chunks only) from the audio sent to speech recognition."""

    def __init__(self, session) -> None:
        self._session = session
        self._resampler = None
        self._input_rate: int | None = None
        self._pending = np.zeros(0, dtype=np.float32)
        self._chunks: list[np.ndarray] = []  # everything heard since the last turn, in CHUNK_SECONDS pieces
        self._tasks: list[asyncio.Task] = []
        self.scores: list[SegmentScore] = []

    def push_frame(self, frame) -> None:
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
            self._chunks.append(piece)
        limit = int(MAX_TURN_SECONDS / CHUNK_SECONDS)
        if len(self._chunks) > limit:
            del self._chunks[: len(self._chunks) - limit]

    def end_turn(self) -> None:
        """Scores the speech heard since the last turn, in the background."""
        speech = speech_regions(self._chunks)[: int(MAX_SEGMENT_SECONDS * SAMPLE_RATE)]
        self._chunks = []
        if len(speech) >= MIN_SEGMENT_SECONDS * SAMPLE_RATE:
            self._tasks.append(asyncio.create_task(self._score(speech)))

    async def _score(self, audio: np.ndarray) -> None:
        self.scores.append(await asyncio.to_thread(self._classify, audio))

    async def decide(self, timeout: float) -> GenderResult:
        """Scores what is left and combines every turn's score."""
        self.end_turn()
        pending = [task for task in self._tasks if not task.done()]
        if pending:
            await asyncio.wait(pending, timeout=timeout)
        return combine(self.scores)

    def _classify(self, audio: np.ndarray) -> SegmentScore:
        started = time.time()
        normalized = (audio - audio.mean()) / (audio.std() + 1e-7)
        logits = self._session.run(None, {"input_values": normalized[None, :].astype(np.float32)})[0][0]
        probabilities = np.exp(logits - logits.max())
        probabilities /= probabilities.sum()
        # Label order of the model: 0 = female, 1 = male.
        return SegmentScore(round(float(probabilities[1]), 3), round(len(audio) / SAMPLE_RATE, 2),
                            round((time.time() - started) * 1000))
