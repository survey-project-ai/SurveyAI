"""Evens out the agent's speaking volume before it goes to the phone line.

v4 Turbo's streamed audio is quiet and uneven: measured on this survey's lines, 300 ms speech windows
ranged from -33 to -48 dBFS, and short replies ("Teşekkür ederim. Kaç yaşındasınız?") came out about
10 dB quieter than long ones. Callers heard the voice sometimes get very quiet, and the office ambience
then sat almost as loud as the voice. This is an automatic gain control: it brings speech towards
TARGET_DB, holds the gain through pauses, and keeps peaks under PEAK_CEILING.
"""
from __future__ import annotations

import numpy as np

TARGET_DB = -22.0  # speech level (short-term RMS) the gain aims for, in dBFS
GATE_DB = -55.0  # below this the audio is a pause: the gain is held, not raised
MAX_GAIN_DB = 24.0
MIN_GAIN_DB = -6.0
INITIAL_GAIN_DB = 15.0  # where a fresh call starts; the gain then carries over between replies
PEAK_CEILING = 0.89  # -1 dBFS
BLOCK_SECONDS = 0.01
LEVEL_SECONDS = 0.15  # how long the speech level is averaged over
GAIN_ATTACK_SECONDS = 0.05  # how fast the gain drops when speech gets louder
GAIN_RELEASE_SECONDS = 0.5  # how fast it rises again; slower so it does not pump


def _coefficient(seconds: float) -> float:
    return float(np.exp(-BLOCK_SECONDS / seconds))


class VoiceLeveler:
    def __init__(self) -> None:
        self._power = 10 ** (TARGET_DB / 10)
        self._gain_db = INITIAL_GAIN_DB

    def process_samples(self, samples: np.ndarray, sample_rate: int) -> np.ndarray:
        """samples: float32 in [-1, 1], mono. Returns the levelled samples."""
        block = max(1, int(sample_rate * BLOCK_SECONDS))
        out = np.empty_like(samples)
        level = _coefficient(LEVEL_SECONDS)
        attack, release = _coefficient(GAIN_ATTACK_SECONDS), _coefficient(GAIN_RELEASE_SECONDS)
        for start in range(0, len(samples), block):
            piece = samples[start:start + block]
            if not len(piece):
                continue
            self._power = level * self._power + (1 - level) * float(np.mean(piece ** 2))
            level_db = 10 * np.log10(self._power + 1e-12)
            previous = self._gain_db
            if level_db > GATE_DB:
                wanted = float(np.clip(TARGET_DB - level_db, MIN_GAIN_DB, MAX_GAIN_DB))
                coefficient = attack if wanted < self._gain_db else release
                self._gain_db = coefficient * self._gain_db + (1 - coefficient) * wanted
            peak = float(np.max(np.abs(piece)))
            if peak > 0:
                # A loud syllable cuts the gain at once instead of clipping.
                self._gain_db = min(self._gain_db, 20 * np.log10(PEAK_CEILING / peak))
            # A rising gain is ramped across the block so it does not click; a falling one applies at once
            # so this block's peak stays under the ceiling.
            start_db = min(previous, self._gain_db)
            gain = 10 ** (np.linspace(start_db, self._gain_db, len(piece), dtype=np.float32) / 20)
            out[start:start + block] = piece * gain
        return np.clip(out, -1.0, 1.0)

    def process_frame(self, frame):
        from livekit import rtc

        samples = np.frombuffer(frame.data, dtype=np.int16).astype(np.float32) / 32768.0
        channels = frame.num_channels
        if channels > 1:
            samples = samples.reshape(-1, channels).mean(axis=1)
        levelled = self.process_samples(samples, frame.sample_rate)
        if channels > 1:
            levelled = np.repeat(levelled, channels)
        return rtc.AudioFrame(
            data=(levelled * 32767).astype(np.int16).tobytes(),
            sample_rate=frame.sample_rate,
            num_channels=channels,
            samples_per_channel=frame.samples_per_channel,
        )
