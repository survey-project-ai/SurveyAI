"""Runs the post-call check (verify_call.py) for every call that ends while this is running.

Starting the check from the agent's own job did not survive once: its process died with an empty log,
while the same check started from outside worked. So it runs here, apart from the agent, like a job a
backend would pick up after the call. Run it next to the agent: python verify_watch.py

A call is checked once its answers (logs/<room>.answers.jsonl) and recording are written and it has no
logs/<room>.final.json yet. Calls that ended more than LOOKBACK_SECONDS before this started are left alone.
"""
from __future__ import annotations

import asyncio
import contextlib
import time
import traceback

from common import LOG_DIR
from verify_call import main as verify

POLL_SECONDS = 5
SETTLE_SECONDS = 3  # the agent writes the recording and the answers one after the other
LOOKBACK_SECONDS = 15 * 60


def waiting_calls(since: float, attempted: set[str]) -> list[str]:
    rooms = []
    for answers in LOG_DIR.glob("*.answers.jsonl"):
        room = answers.name.removesuffix(".answers.jsonl")
        modified = answers.stat().st_mtime
        if (room in attempted or modified < since or time.time() - modified < SETTLE_SECONDS
                or (LOG_DIR / f"{room}.final.json").exists() or not (LOG_DIR / f"{room}.callee.wav").exists()):
            continue
        rooms.append(room)
    return sorted(rooms)


async def watch() -> None:
    since, attempted = time.time() - LOOKBACK_SECONDS, set()
    print(f"watching {LOG_DIR} for finished calls", flush=True)
    while True:
        for room in waiting_calls(since, attempted):
            attempted.add(room)
            started = time.time()
            with (LOG_DIR / f"{room}.verify.log").open("w", encoding="utf-8") as log, contextlib.redirect_stdout(log):
                try:
                    await verify(room)
                except Exception:  # one failed check must not stop the others
                    traceback.print_exc(file=log)
            status = "done" if (LOG_DIR / f"{room}.final.json").exists() else "FAILED, see .verify.log"
            print(f"{room}: {status} in {time.time() - started:.0f}s", flush=True)
        await asyncio.sleep(POLL_SECONDS)


if __name__ == "__main__":
    asyncio.run(watch())
