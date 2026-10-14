"""Keeps the agent's spoken replies on disk.

A reply that was said before plays at once instead of waiting for speech synthesis (about 0.15 s to the
first audio), and sounds the same in every call. Replies are keyed by voice, model and exact text; the
main ones are synthesized while the phone rings (see agent.py), the rest are stored the first time they
are said.
"""
from __future__ import annotations

import hashlib
import wave
from pathlib import Path

from livekit import rtc

CACHE_DIR = Path(__file__).resolve().parent / "cache" / "tts"
FRAME_SECONDS = 0.02


class TtsCache:
    def __init__(self, voice_id: str, model: str) -> None:
        self._key_prefix = f"{voice_id}|{model}|"

    def _path(self, text: str) -> Path:
        digest = hashlib.sha1((self._key_prefix + text).encode("utf-8")).hexdigest()
        return CACHE_DIR / f"{digest}.wav"

    def has(self, text: str) -> bool:
        return self._path(text).exists()

    def get(self, text: str) -> list[rtc.AudioFrame] | None:
        path = self._path(text)
        if not path.exists():
            return None
        with wave.open(str(path)) as handle:
            rate, channels = handle.getframerate(), handle.getnchannels()
            data = handle.readframes(handle.getnframes())
        step = int(rate * FRAME_SECONDS) * 2 * channels
        return [rtc.AudioFrame(data[i:i + step], rate, channels, len(data[i:i + step]) // (2 * channels))
                for i in range(0, len(data), step)]

    def put(self, text: str, frames: list[rtc.AudioFrame]) -> None:
        if not frames:
            return
        CACHE_DIR.mkdir(parents=True, exist_ok=True)
        path = self._path(text)
        partial = path.with_suffix(".partial")
        with wave.open(str(partial), "wb") as handle:
            handle.setnchannels(frames[0].num_channels)
            handle.setsampwidth(2)
            handle.setframerate(frames[0].sample_rate)
            handle.writeframes(b"".join(bytes(frame.data) for frame in frames))
        partial.replace(path)
