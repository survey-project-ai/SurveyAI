"""Runs a call the backend placed: the backend interview engine decides what the agent says, and the agent
reports the call's progress back to the backend.

The engine is reached through the interview tool API (/api/v1/provider-tools/interviews/*), with the
LIVEKIT_TOOL_API_SECRET in X-SurveyAI-Tool-Secret. Call events go to /api/v1/provider-webhooks/LIVEKIT,
signed with LIVEKIT_WEBHOOK_SECRET; LiveKitVoiceExecutionProvider in the backend describes them.
"""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import logging
import time
from dataclasses import dataclass
from typing import Callable

import aiohttp

from phrases import ALREADY_CALLED, CALLED_AGAIN, HEARD, has_any, is_line_check, normalize, signal_for

logger = logging.getLogger("survey-backend")

PLEASE_WAIT = "Bir saniye lütfen."
TECHNICAL_GOODBYE = ("Kusura bakmayın, şu an bir teknik sorun yaşıyoruz. Sizi daha sonra tekrar arayacağız. "
                     "İyi günler dilerim.")
TOOL_TIMEOUT = aiohttp.ClientTimeout(total=5)
EVENT_TIMEOUT = aiohttp.ClientTimeout(total=10)
EVENT_RETRY_DELAYS = (0.5, 2.0)


class BackendError(Exception):
    pass


@dataclass(frozen=True)
class BackendSettings:
    url: str
    tool_secret: str
    webhook_secret: str


class InterviewClient:
    """The backend calls of one call attempt. The job metadata comes from the backend's dispatch."""

    def __init__(self, http: aiohttp.ClientSession, settings: BackendSettings, job: dict, room: str) -> None:
        self.http, self.settings, self.job, self.room = http, settings, job, room
        self.call_attempt_id = job["callAttemptId"]
        # Events go out one at a time, in the order the call produced them.
        self._report_lock = asyncio.Lock()

    async def start(self) -> dict:
        return await self._tool("start", {"callAttemptId": self.call_attempt_id})

    async def answer(self, text: str, signal: str, question_id: str | None) -> dict:
        return await self._tool("answer", {"callAttemptId": self.call_attempt_id, "utteranceText": text,
                                           "signal": signal, "questionId": question_id})

    async def finish(self) -> dict:
        # The agent says its own goodbye, or the callee is already gone.
        return await self._tool("finish", {"callAttemptId": self.call_attempt_id, "suppressClosingMessage": True})

    async def _tool(self, name: str, body: dict) -> dict:
        url = f"{self.settings.url}/api/v1/provider-tools/interviews/{name}"
        started = time.monotonic()
        try:
            async with self.http.post(url, json=body, timeout=TOOL_TIMEOUT,
                                      headers={"X-SurveyAI-Tool-Secret": self.settings.tool_secret}) as response:
                if response.status >= 400:
                    raise BackendError(f"{name}: HTTP {response.status} {(await response.text())[:200]}")
                result = await response.json()
        except (aiohttp.ClientError, asyncio.TimeoutError) as error:
            raise BackendError(f"{name}: {type(error).__name__} {error}") from error
        logger.info("backend %s took %d ms", name, round((time.monotonic() - started) * 1000))
        return result

    async def report(self, event: str, **fields) -> bool:
        """Posts a call event, retrying a few times: a lost final event makes the backend fail the call."""
        payload = {
            "event": event,
            "room": self.room,
            "callAttemptId": self.call_attempt_id,
            "callJobId": self.job.get("callJobId"),
            "operationId": self.job.get("operationId"),
            "contactId": self.job.get("contactId"),
            "idempotencyKey": self.job.get("idempotencyKey"),
            "timestamp": time.time(),
            **{key: value for key, value in fields.items() if value is not None},
        }
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        async with self._report_lock:
            return await self._post_event(event, body)

    async def _post_event(self, event: str, body: bytes) -> bool:
        for attempt, delay in enumerate((0.0, *EVENT_RETRY_DELAYS)):
            await asyncio.sleep(delay)
            timestamp = str(int(time.time()))
            signature = hmac.new(self.settings.webhook_secret.encode("utf-8"), timestamp.encode() + b"." + body,
                                 hashlib.sha256).hexdigest()
            try:
                async with self.http.post(f"{self.settings.url}/api/v1/provider-webhooks/LIVEKIT", data=body,
                                          timeout=EVENT_TIMEOUT, headers={
                                              "Content-Type": "application/json",
                                              "X-SurveyAI-Signature": f"t={timestamp},v1={signature}",
                                          }) as response:
                    if response.status < 300:
                        logger.info("reported %s", event)
                        return True
                    text = (await response.text())[:200]
                    if response.status < 500:  # rejected; sending it again will not help
                        logger.error("backend rejected the %s event: HTTP %d %s", event, response.status, text)
                        return False
                    logger.warning("reporting %s failed (attempt %d): HTTP %d %s", event, attempt + 1, response.status, text)
            except (aiohttp.ClientError, asyncio.TimeoutError) as error:
                logger.warning("reporting %s failed (attempt %d): %s", event, attempt + 1, error)
        return False


class BackendInterview:
    """The conversation of a call the backend placed. The engine interprets every answer; the agent only
    picks the signal and keeps the turns the engine should not see.

    step: "greeting" until the opening is said, "interview" during it, "done" once the call is to end.
    """

    def __init__(self, client: InterviewClient, say: Callable[[str], object]) -> None:
        self.client = client
        self.say = say  # speaks a line at once, without waiting for the reply
        self.step = "greeting"
        # The engine's current question (InterviewQuestionPayload), None during the opening and consent.
        self.question: dict | None = None
        self.last_prompt: str | None = None
        # What a survey without an opening asks first, said once the callee has picked up and greeted.
        self.opening: str | None = None
        # The callee's last reply when the engine asked the same question again; read together with what
        # follows if the callee talked over that retry (speech recognition split one answer in two).
        self.unanswered: str | None = None
        # Set once /start has answered: the callee's first words wait for it.
        self.started = asyncio.Event()

    async def begin(self) -> None:
        """Called when the callee picks up. The callee speaks first ("Alo"), so nothing is said yet."""
        try:
            await self._begin()
        finally:
            self.started.set()

    async def _begin(self) -> None:
        # A failure here is not spoken: the callee has not said a word yet, and the greeting goes to the
        # engine again anyway.
        response = await self._call(self.client.start, retry=False)
        if response is None:
            return
        if response.get("endCall"):  # the interview already ended on an earlier call
            self.step = "done"
            return
        self.opening = response.get("prompt")
        self.question = response.get("question")

    def takes_fillers(self) -> bool:
        return False

    def first_replies(self) -> list[str]:
        return [PLEASE_WAIT]

    async def reply(self, user_text: str, previous_reply_missed: bool = False, heard_at_s=None) -> tuple[str | None, bool]:
        # Before /start answers, a survey without an opening would take the callee's "Alo" for an answer.
        await self.started.wait()
        if self.step == "greeting" and self.opening:
            # A survey without an opening: the callee's "Alo" is not the answer to the first question.
            self.step = "interview"
            return self._remember(self.opening), False
        if self.step != "greeting" and is_line_check(user_text):
            current = (self.question or {}).get("spokenPrompt") or self.last_prompt or ""
            return f"{HEARD} {current}".strip(), False

        signal = "ANSWER" if self.step == "greeting" else signal_for(user_text)
        utterance = user_text
        if previous_reply_missed and self.unanswered and signal == "ANSWER":
            utterance = f"{self.unanswered} {user_text}"
        question_id = (self.question or {}).get("id")
        response = await self._call(self.client.answer, utterance, signal, question_id)
        if response is None:
            self.step = "done"
            return TECHNICAL_GOODBYE, True

        if self.step == "greeting" and response.get("prompt"):
            self.step = "interview"
        next_question = response.get("question")
        retried = (next_question is not None and question_id is not None and next_question.get("id") == question_id
                   and next_question.get("retryNeeded"))
        self.unanswered = utterance if retried and signal == "ANSWER" else None
        self.question = next_question

        end_call = bool(response.get("endCall"))
        prompt = response.get("closingMessage") if end_call else response.get("prompt")
        if end_call:
            self.step = "done"
        elif prompt and has_any(normalize(user_text), *ALREADY_CALLED):
            prompt = f"{CALLED_AGAIN} {prompt}"
        return self._remember(prompt), end_call

    async def finish(self) -> None:
        await self._call(self.client.finish, retry=False)

    def _remember(self, prompt: str | None) -> str | None:
        if prompt:
            self.last_prompt = prompt
        return prompt

    async def _call(self, method, *args, retry: bool = True):
        try:
            return await method(*args)
        except BackendError as error:
            logger.error("backend call failed: %s", error)
            if not retry:
                return None
        # One more try, with a word to the callee so the silence is not taken for a dropped line.
        self.say(PLEASE_WAIT)
        try:
            return await method(*args)
        except BackendError as error:
            logger.error("backend call failed again: %s", error)
            return None
