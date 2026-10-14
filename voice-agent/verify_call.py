"""Checks a finished call's survey answers against a second listening of the recording.

Speech recognition during the call has to decide in a fraction of a second, and it made mistakes on
short answers that the same models got right from the recording ("Belli bir" for "elli bir",
"atıyorum" for "kapatıyorum"). After the call, each reply to a survey question is cut from
logs/<room>.callee.wav, transcribed again by CHECK_MODELS, and read with the same rules as in the call:

- "doğrulandı": every check gives the value recorded in the call
- "düzeltme önerisi": the checks agree with each other on a different value
- "kontrol edilecek": anything else; someone should listen to the clip
- "kaçırılan cevap": nothing was recorded in the call, but a check heard an answer
- "cevap yok": no answer either way; the checks' text shows what was said

agent.py starts this when a call ends; it can also be run by hand: python verify_call.py <room>
Writes logs/<room>.verified.json.
"""
from __future__ import annotations

import asyncio
import json
import sys
import time
import wave

import aiohttp
import numpy as np

from common import LOG_DIR, load_keys

load_keys()
from livekit import rtc  # noqa: E402
from livekit.agents import inference  # noqa: E402
from livekit.agents import stt as agents_stt  # noqa: E402

from agent import SurveyFlow, normalize  # noqa: E402

# Linden-1 got every key answer of the recorded test calls right; Gemini is the second opinion.
CHECK_MODELS = ["speechmatics/linden-1", "google/gemini-3.5-transcribe-live"]
RATE = 16000
CHUNK_SECONDS = 0.02


def load_callee(room: str) -> np.ndarray:
    with wave.open(str(LOG_DIR / f"{room}.callee.wav")) as handle:
        rate = handle.getframerate()
        audio = np.frombuffer(handle.readframes(handle.getnframes()), dtype=np.int16)
    resampler = rtc.AudioResampler(rate, RATE, num_channels=1, quality=rtc.AudioResamplerQuality.HIGH)
    frames = resampler.push(rtc.AudioFrame(audio.tobytes(), rate, 1, len(audio))) + resampler.flush()
    return np.concatenate([np.frombuffer(frame.data, dtype=np.int16) for frame in frames])


def answer_clip(audio: np.ndarray, heard_at: float, previous_at: float | None) -> np.ndarray | None:
    """The last stretch of speech before the turn was taken (pauses under 0.8 s belong to it)."""
    n = int(RATE * CHUNK_SECONDS)
    start_s = max((previous_at or 0) + 0.3, heard_at - 8)
    lo, hi = int(start_s * RATE) // n, int(heard_at * RATE) // n
    chunks = audio[lo * n: hi * n].astype(np.float32).reshape(-1, n) / 32768
    if not len(chunks):
        return None
    rms = np.sqrt((chunks ** 2).mean(axis=1))
    loud = np.flatnonzero(rms > max(0.003, 0.1 * rms.max()))
    if not len(loud):
        return None
    first = loud[-1]
    for earlier, later in zip(loud[::-1][1:], loud[::-1][:-1]):
        if (later - earlier) * CHUNK_SECONDS > 0.8:
            break
        first = earlier
    begin = max(0, (lo + first) * n - int(0.25 * RATE))
    end = min(len(audio), (lo + loud[-1] + 1) * n + int(0.3 * RATE))
    return audio[begin:end]


async def transcribe(http: aiohttp.ClientSession, model: str, clip: np.ndarray) -> str:
    stream = inference.STT(model=model, language="tr", http_session=http).stream()
    finals: list[str] = []

    async def collect() -> None:
        async for event in stream:
            if event.type == agents_stt.SpeechEventType.FINAL_TRANSCRIPT and event.alternatives:
                text = event.alternatives[0].text.strip()
                if text:
                    finals.append(text)

    collector = asyncio.create_task(collect())
    padded = np.concatenate([np.zeros(RATE // 4, dtype=np.int16), clip, np.zeros(RATE, dtype=np.int16)])
    for i in range(0, len(padded), RATE // 10):
        piece = padded[i:i + RATE // 10]
        stream.push_frame(rtc.AudioFrame(piece.tobytes(), RATE, 1, len(piece)))
        await asyncio.sleep(0.05)  # twice real time
    stream.end_input()
    try:
        await asyncio.wait_for(collector, 8)
    except asyncio.TimeoutError:
        collector.cancel()
    await stream.aclose()
    return " ".join(finals)


def read_answer(question: str, text: str) -> str | None:
    answer = SurveyFlow.interpret(question, text, normalize(text)) if text else None
    return None if answer is None else answer[0]


def verdict(recorded: str | None, checked: list[str | None]) -> tuple[str, str | None]:
    if all(value == recorded for value in checked):
        return "doğrulandı", None
    if checked and checked[0] is not None and all(value == checked[0] for value in checked):
        return "düzeltme önerisi", checked[0]
    return "kontrol edilecek", None


async def main(room: str) -> None:
    started = time.time()
    audio = load_callee(room)
    turns = [json.loads(line) for line in (LOG_DIR / f"{room}.answers.jsonl").read_text(encoding="utf-8").splitlines()]
    results = []
    async with aiohttp.ClientSession() as http:
        for turn in turns:
            clip = answer_clip(audio, turn["heard_at_s"], turn.get("previous_heard_at_s"))
            entry = {"question": turn["question"], "heard_at_s": turn["heard_at_s"], "in_call_text": turn["text"],
                     "recorded": turn["recorded"], "checks": {}}
            if clip is None:
                entry.update(status="kontrol edilecek", note="kayıtta konuşma bulunamadı")
                results.append(entry)
                continue
            for model in CHECK_MODELS:
                text = await transcribe(http, model, clip)
                entry["checks"][model] = {"text": text, "value": read_answer(turn["question"], text)}
            checked = [check["value"] for check in entry["checks"].values()]
            if turn["recorded"] is not None:
                status, suggested = verdict(turn["recorded"], checked)
            elif any(value is not None for value in checked):
                # Nothing was recorded in the call, but the recording holds an answer.
                status, suggested = "kaçırılan cevap", next(value for value in checked if value is not None)
            else:
                # No answer either way; the checks' text shows what was said ("Tamam, kapatıyorum").
                status, suggested = "cevap yok", None
            entry["status"] = status
            if suggested is not None:
                entry["suggested"] = suggested
            results.append(entry)
    (LOG_DIR / f"{room}.verified.json").write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
    for entry in results:
        checks = "; ".join(f"{model.split('/')[0]}: {c['text']!r}" for model, c in entry["checks"].items())
        print(f"{entry['status']:17} {entry['question']:9} call: {entry['in_call_text']!r} -> {entry['recorded']!r}"
              f"{'  suggested: ' + repr(entry['suggested']) if 'suggested' in entry else ''}  [{checks}]")
    print(f"checked {len(results)} answers in {time.time() - started:.1f}s")


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1]))
