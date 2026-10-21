"""Voice agent: places one outbound call through the Netgsm trunk and speaks each reply directly, without an
LLM. Every turn's timings are written to logs/<room>.jsonl.

A call the backend places (its job metadata carries a callAttemptId) runs the backend interview engine
(backend.py) and reports its progress to the backend. A call placed with call.py runs the Phase 0
prototype's own survey (prototype_flow.py).

Run the worker:   .venv/Scripts/python agent.py dev
Place a call:     .venv/Scripts/python call.py +905xxxxxxxxx   (prototype survey)
"""
import asyncio
import faulthandler
import json
import logging
import time
import wave

import aiohttp
import numpy as np
from livekit import api
from livekit.agents import (
    Agent,
    AgentServer,
    AgentSession,
    AudioConfig,
    BackgroundAudioPlayer,
    BuiltinAudioClip,
    JobContext,
    JobProcess,
    MetricsCollectedEvent,
    StopResponse,
    cli,
    metrics,
)
from livekit.agents import inference
from livekit.plugins import elevenlabs, silero

from backend import BackendInterview, InterviewClient, dial_failure_event
from common import (AGENT_NAME, AMBIENT_VOLUME, BACKEND_URL, LOG_DIR, STT_MODEL, TTS_MODEL, VOICE_ID, backend_settings,
                    load_keys, read_state)
from gender import VoiceGenderDetector, load_model
from leveler import VoiceLeveler
from phrases import FILLERS, is_hesitation, may_continue_number, normalize
from prototype_flow import KEYTERMS, PrototypeInterview
from tts_cache import TtsCache

load_keys()
logger = logging.getLogger("survey-agent")
# How long a turn ending in "on", "yirmi"... waits for the rest of the number. The second half of the
# split "On dokuz" arrived 0.7 s after the first.
NUMBER_CONTINUATION_WAIT = 1.0
# A native crash (the worker once aborted with no log while a call ended) then at least prints every
# thread's Python stack to the log.
faulthandler.enable()


class TurnRecorder:
    """Collects per-turn timings: callee stops speaking -> agent audio starts.

    "latency" runs from the last loud frame of the callee's audio. LiveKit's own end-of-speech event
    ("latency_after_vad") comes about a second later: in the 2026-10-14 calls it logged 0.7 s for turns
    that took 1.6-1.9 s from the end of speech in the recording.
    """

    def __init__(self, room_name: str) -> None:
        LOG_DIR.mkdir(exist_ok=True)
        self.path = LOG_DIR / f"{room_name}.jsonl"
        self.user_stopped_at: float | None = None
        self.pending: dict | None = None
        self.turns: list[dict] = []
        # The conversation as said and heard, for the backend's call record.
        self.transcript: list[dict] = []

    def user_state(self, new_state: str, at: float) -> None:
        if new_state == "listening":
            self.user_stopped_at = at

    def heard(self, user_text: str) -> None:
        # The callee's turns never reach "conversation_item_added": the agent stops LiveKit's own reply
        # (StopResponse) and speaks its own, so they are written here as they arrive.
        if user_text.strip():
            self.transcript.append({"role": "user", "text": user_text.strip()})

    def start_turn(self, step: str, user_text: str, speech_end_at: float | None) -> dict:
        self.pending = {"step": step, "user_text": user_text, "user_stopped_at": self.user_stopped_at,
                        "speech_end_at": speech_end_at}
        return self.pending

    def eou(self, m: metrics.EOUMetrics) -> None:
        if self.pending is not None:
            self.pending.update(
                end_of_utterance_delay=round(m.end_of_utterance_delay, 3),
                transcription_delay=round(m.transcription_delay, 3),
            )

    def tts(self, m: metrics.TTSMetrics) -> None:
        if self.pending is not None and "tts_ttfb" not in self.pending:
            self.pending["tts_ttfb"] = round(m.ttfb, 3)

    def message(self, item) -> None:
        """Logs LiveKit's own per-message metrics. For a reply, e2e is measured from the moment the
        callee actually stopped speaking (not from when the pause was detected)."""
        role = getattr(item, "role", None)
        item_metrics = dict(getattr(item, "metrics", None) or {})
        text = getattr(item, "text_content", None)
        entry = {"role": role, "text": text}
        if role == "user":
            self.last_user_metrics = item_metrics
            entry.update({k: round(v, 3) for k, v in item_metrics.items()
                          if k in ("transcription_delay", "end_of_turn_delay", "on_user_turn_completed_delay") and v is not None})
        elif role == "assistant":
            user = getattr(self, "last_user_metrics", {}) or {}
            if item_metrics.get("started_speaking_at") and user.get("stopped_speaking_at"):
                entry["e2e_from_speech_end"] = round(item_metrics["started_speaking_at"] - user["stopped_speaking_at"], 3)
            if item_metrics.get("tts_node_ttfb") is not None:
                entry["tts_ttfb"] = round(item_metrics["tts_node_ttfb"], 3)
            self.last_user_metrics = {}
        if role == "assistant" and text:
            self.transcript.append({"role": "agent", "text": text})
        with (self.path.parent / (self.path.stem + ".messages.jsonl")).open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(entry, ensure_ascii=False) + "\n")
        logger.info("message %s", entry)

    def agent_speaking(self, at: float) -> None:
        turn, self.pending = self.pending, None
        if turn is None:
            return
        speech_end_at = turn.pop("speech_end_at", None)
        if speech_end_at:
            turn["latency"] = round(at - speech_end_at, 3)
        user_stopped_at = turn.pop("user_stopped_at", None)
        if user_stopped_at:
            turn["latency_after_vad"] = round(at - user_stopped_at, 3)
        self.turns.append(turn)
        with self.path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(turn, ensure_ascii=False) + "\n")
        logger.info("turn %s", turn)


class SurveyAgent(Agent):
    def __init__(self, interview, recorder: TurnRecorder, hang_up, gender: VoiceGenderDetector) -> None:
        super().__init__(instructions="Fixed-script survey; every reply comes from the interview.")
        self.interview, self.recorder, self.hang_up = interview, recorder, hang_up
        self.gender = gender
        self.leveler = VoiceLeveler()
        self.tts_cache = TtsCache(VOICE_ID, TTS_MODEL)
        # What the agent sent to the line, one reply after another, written to logs/ when the call ends.
        self.sent_audio: list[bytes] = []
        self.sent_sample_rate = 16000
        # The callee's side as it reached speech recognition, in real time, to check transcripts against.
        self.heard_audio: list[bytes] = []
        self.heard_sample_rate = 16000
        self.heard_started_at: float | None = None
        self.heard_peak = 0.0
        self.last_voice_at: float | None = None  # wall time of the callee's last loud audio frame
        self.last_reply = None
        # A turn ending in a number that may go on ("On."), waiting briefly for the rest ("Dokuz.").
        self.held_text: str | None = None
        self.held_release: asyncio.Task | None = None

    async def stt_node(self, audio, model_settings):
        # The callee's audio also goes to the gender detector and the recording.
        async def tee():
            async for frame in audio:
                self.gender.push_frame(frame)
                self._keep_heard(frame)
                yield frame

        async for event in Agent.default.stt_node(self, tee(), model_settings):
            yield event

    def _keep_heard(self, frame) -> None:
        if self.heard_started_at is None:
            self.heard_started_at = time.time()
            self.heard_sample_rate = frame.sample_rate
        samples = np.frombuffer(frame.data, dtype=np.int16)
        if frame.num_channels > 1:
            samples = samples[::frame.num_channels]
        self.heard_audio.append(samples.tobytes())
        rms = float(np.sqrt(np.mean((samples.astype(np.float32) / 32768) ** 2))) if len(samples) else 0.0
        self.heard_peak = max(self.heard_peak, rms)
        if rms > max(0.003, 0.1 * self.heard_peak):  # above line noise and within 20 dB of the loudest speech
            self.last_voice_at = time.time()

    def heard_offset(self) -> float | None:
        """Seconds into the callee recording, so a transcript can be found in it."""
        return None if self.heard_started_at is None else round(time.time() - self.heard_started_at, 1)

    def _last_reply_missed(self) -> bool:
        """True when the previous reply was interrupted before most of it was played."""
        speech = self.last_reply
        if speech is None or not speech.interrupted:
            return False
        said = getattr(speech, "_reply_text", "")
        heard = " ".join(item.text_content or "" for item in speech.chat_items if getattr(item, "role", None) == "assistant")
        return len(heard) < 0.6 * len(said)

    async def tts_node(self, text, model_settings):
        # Replies come whole from session.say(), so the full text is known before synthesis starts.
        reply = "".join([part async for part in text])
        cached = self.tts_cache.get(reply)
        if cached is not None:
            source = iter_frames(cached)
        else:
            source = self._synthesize_and_keep(reply, model_settings)
        # One leveler for the whole call, so each reply starts at the gain the previous one ended with.
        async for frame in source:
            levelled = self.leveler.process_frame(frame)
            self.sent_sample_rate = levelled.sample_rate
            self.sent_audio.append(bytes(levelled.data))
            yield levelled
        self.sent_audio.append(bytes(int(self.sent_sample_rate * 0.5) * 2))  # half a second between replies

    async def _synthesize_and_keep(self, reply: str, model_settings):
        async def once():
            yield reply

        frames = []
        async for frame in Agent.default.tts_node(self, once(), model_settings):
            frames.append(frame)
            yield frame
        # Only a reply synthesized to the end is kept; an interrupted one stops at the yield above.
        self.tts_cache.put(reply, frames)

    def save_audio(self, room_name: str) -> None:
        write_wav(LOG_DIR / f"{room_name}.agent.wav", self.sent_audio, self.sent_sample_rate)
        write_wav(LOG_DIR / f"{room_name}.callee.wav", self.heard_audio, self.heard_sample_rate)

    async def on_user_turn_completed(self, turn_ctx, new_message) -> None:
        user_text = new_message.text_content or ""
        self.recorder.heard(user_text)
        if self.held_text is not None:
            # The rest of a number held back below: "On." + "Dokuz." -> "On. Dokuz."
            self.held_release.cancel()
            user_text = f"{self.held_text} {user_text}"
            self.held_text = self.held_release = None
        if self.interview.step == "done":
            # The survey is over and the goodbye is playing; once the end of the callee's own sentence
            # ("...diyeceğim") arrived here and crashed the handler.
            logger.info("ignoring %r: the survey is over", user_text)
            raise StopResponse()
        if (self.last_reply is not None and not self.last_reply.done() and normalize(user_text) in FILLERS
                and not self.interview.takes_fillers()):
            logger.info("ignoring %r: the previous reply has not been played yet", user_text)
            raise StopResponse()
        if self.interview.step != "greeting" and is_hesitation(user_text):
            logger.info("ignoring %r: the callee is thinking", user_text)
            raise StopResponse()
        if self.interview.step != "greeting" and may_continue_number(user_text):
            # Speech recognition once gave "On dokuz" as two turns; "on" was then recorded as the age.
            self.held_text = user_text
            self.held_release = asyncio.create_task(self._release_held())
            logger.info("holding %r: the number may go on", user_text)
            raise StopResponse()
        await self._respond(user_text)
        raise StopResponse()

    async def _release_held(self) -> None:
        """Answers a held number once no more of it has arrived."""
        await asyncio.sleep(NUMBER_CONTINUATION_WAIT)
        text, self.held_text, self.held_release = self.held_text, None, None
        await self._respond(text)

    async def _respond(self, user_text: str) -> None:
        turn = self.recorder.start_turn(self.interview.step, user_text, self.last_voice_at)
        turn["heard_at_s"] = self.heard_offset()
        started = time.time()
        # Scores this turn's voice in the background; the gender is decided when the call ends.
        self.gender.end_turn()
        missed = self._last_reply_missed()
        if missed:
            turn["previous_reply_missed"] = True
        opening = self.interview.step == "greeting"
        reply, end_call = await self.interview.reply(user_text, previous_reply_missed=missed,
                                                     heard_at_s=turn["heard_at_s"])
        turn["handler_ms"] = round((time.time() - started) * 1000)
        turn["reply"] = reply
        if not reply:
            # Nothing to say yet (the engine waits for a greeting it can recognise).
            self.recorder.pending = None
            return
        # The opening is said in full even if the callee says "Alo?" again over it, as a person would; in the
        # second test call a repeated "Alo" cancelled it before it started and the callee never heard it.
        # The goodbye too: the tail of the callee's last answer once cut it after "Vakit" and the line dropped.
        speech = self.session.say(reply, allow_interruptions=not opening and not end_call)
        speech._reply_text = reply
        self.last_reply = speech
        if end_call:
            asyncio.create_task(self.hang_up(speech))


async def iter_frames(frames):
    """Cached audio, paced at about 4x real time after the first half second. Handing LiveKit a whole
    reply at once blocked its event loop for ~200 ms (transcript sync), which delayed the reply's start."""
    for index, frame in enumerate(frames):
        yield frame
        await asyncio.sleep(0.005 if index >= 25 else 0)


async def synthesize_missing(tts, cache: TtsCache, replies: list[str]) -> None:
    """Fills the reply cache in the background; a reply that is not ready in time is synthesized as usual."""
    async def one(reply: str) -> None:
        stream = tts.stream()
        try:
            stream.push_text(reply)
            stream.end_input()
            cache.put(reply, [event.frame async for event in stream])
            logger.info("cached reply audio: %r", reply[:40])
        except Exception as error:  # a missing cache entry only costs the usual synthesis time
            logger.warning("could not cache %r: %s", reply[:40], error)
        finally:
            await stream.aclose()

    await asyncio.gather(*(one(reply) for reply in replies if not cache.has(reply)))


def write_wav(path, chunks: list[bytes], sample_rate: int) -> None:
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(sample_rate)
        handle.writeframes(b"".join(chunks))


def speech_recognizer():
    if STT_MODEL.startswith("scribe"):
        return elevenlabs.STT(model=STT_MODEL, language_code="tr",
                              server_vad=elevenlabs.stt.VADOptions(vad_silence_threshold_secs=0.5),
                              keyterms=KEYTERMS)
    # LiveKit Inference; Speechmatics Linden-1 decides the end of each turn on its side.
    return inference.STT(model=STT_MODEL, language="tr")


def sip_status(error: api.TwirpError) -> tuple[int | None, str | None]:
    code = error.metadata.get("sip_status_code")
    try:
        return (int(code) if code else None), error.metadata.get("sip_status")
    except ValueError:
        return None, error.metadata.get("sip_status")


def prewarm(proc: JobProcess) -> None:
    proc.userdata["vad"] = silero.VAD.load()
    proc.userdata["gender_model"] = load_model()


# One process is kept ready so the call does not wait for process start-up.
server = AgentServer(setup_fnc=prewarm, num_idle_processes=1, initialize_process_timeout=60.0)


@server.rtc_session(agent_name=AGENT_NAME)
async def entrypoint(ctx: JobContext) -> None:
    metadata = json.loads(ctx.job.metadata or "{}")
    placed_by_backend = "callAttemptId" in metadata
    phone = metadata["phoneNumber"] if placed_by_backend else metadata["phone"]
    trunk_id = metadata.get("sipTrunkId") or read_state()["sip_trunk_id"]
    await ctx.connect()

    recorder = TurnRecorder(ctx.room.name)
    gender = VoiceGenderDetector(ctx.proc.userdata["gender_model"])
    http = aiohttp.ClientSession()
    session = AgentSession(
        vad=ctx.proc.userdata["vad"],
        # Scribe realtime only finalizes a transcript on a commit; with server VAD it commits after
        # 0.5 s of silence, and that commit ends the user's turn.
        stt=speech_recognizer(),
        tts=elevenlabs.TTS(
            voice_id=VOICE_ID,
            model=TTS_MODEL,
            language="tr",
            # Uncompressed audio like the ElevenLabs agent (the plugin default is 32 kbps MP3), and one
            # continuous generation per reply instead of a fresh one per sentence, which made the volume
            # jump at each new sentence.
            encoding="pcm_16000",
            auto_mode=False,
            # v4 Turbo goes through the text-to-dialogue API and ignores similarity_boost and speed; a higher
            # stability barely steadied the pitch (largest 300 ms jump 3.9 -> 3.2 semitones at 1.0).
            voice_settings=elevenlabs.VoiceSettings(stability=0.5, similarity_boost=0.8, speed=1.02),
        ),
        turn_handling={
            "turn_detection": "stt",
            # Scribe already waits 0.5 s of silence before its final transcript, and LiveKit then waited
            # another 0.5 s by default before taking the turn (0.48-0.51 s in every measured turn).
            "endpointing": {"min_delay": 0.0},
            # A short noise must not cut the agent off; if no real speech follows, the agent resumes.
            "interruption": {"min_duration": 0.6, "resume_false_interruption": True},
        },
    )

    client = InterviewClient(http, backend_settings(), metadata, ctx.room.name) if placed_by_backend else None
    if client is not None:
        interview = BackendInterview(client, say=lambda text: session.say(text, allow_interruptions=False))
    else:
        interview = PrototypeInterview()
    answered_at: float | None = None
    reports: list[asyncio.Task] = []

    def report(event: str, **fields) -> None:
        """Sends a call event to the backend in the background, in order (calls the backend placed only)."""
        if client is not None:
            reports.append(asyncio.create_task(client.report(event, **fields)))

    async def hang_up(speech) -> None:
        await speech.wait_for_playout()
        await asyncio.sleep(0.5)
        # Hang up the phone, then leave the room ourselves. Deleting the room instead cut the agent's
        # connection from the server side, and the worker once crashed at exactly that moment.
        try:
            await ctx.api.room.remove_participant(api.RoomParticipantIdentity(room=ctx.room.name, identity="callee"))
        except api.TwirpError as error:  # the callee may have hung up already
            logger.info("callee already gone: %s", error.message)
        ctx.shutdown(reason="survey finished")

    @session.on("user_state_changed")
    def _user_state(event) -> None:
        recorder.user_state(event.new_state, event.created_at)

    @session.on("agent_state_changed")
    def _agent_state(event) -> None:
        if event.new_state == "speaking":
            recorder.agent_speaking(event.created_at)

    @session.on("metrics_collected")
    def _metrics(event: MetricsCollectedEvent) -> None:
        if isinstance(event.metrics, metrics.EOUMetrics):
            recorder.eou(event.metrics)
        elif isinstance(event.metrics, metrics.TTSMetrics):
            recorder.tts(event.metrics)

    @session.on("conversation_item_added")
    def _item(event) -> None:
        recorder.message(event.item)

    @ctx.room.on("participant_connected")
    def _line_joined(participant) -> None:
        sip = {key: value for key, value in participant.attributes.items() if key.startswith("sip.")}
        if sip:
            logger.info("line %s: %s", participant.identity, sip)

    @ctx.room.on("participant_attributes_changed")
    def _line_status(changed: dict, participant) -> None:
        # The phone line's progress (dialing, ringing, active, hangup): whether a dial nobody answered
        # rang at all. One number timed out four times without a SIP status.
        sip = {key: value for key, value in changed.items() if key.startswith("sip.")}
        if sip:
            logger.info("line %s: %s", participant.identity, sip)

    @session.on("close")
    def _closed(event) -> None:
        # The callee hung up (or the session failed): end the job, which ends the interview.
        ctx.shutdown(reason=f"session closed: {event.reason}")

    async def finish_call(reason: str) -> None:
        survey_agent.save_audio(ctx.room.name)
        if isinstance(interview, PrototypeInterview):
            # verify_watch.py picks the call up from logs/ and checks the answers.
            interview.save_answer_turns(LOG_DIR / f"{ctx.room.name}.answers.jsonl")
        decision = await gender.decide(timeout=2.0)
        # Never asked: the backend records it as the gender question's answer (source "voice").
        inferred = ({"gender": {"value": decision.label, "confidence": round(decision.confidence, 3)}}
                    if decision.label else None)
        if client is None:
            await interview.finish(inferred)  # the prototype notes it with its own answers
        logger.info("call finished (%s): %d turns, answers=%s, gender=%s, scores=%s", reason, len(recorder.turns),
                    getattr(getattr(interview, "flow", None), "answers", "in the backend"), decision,
                    [score.__dict__ for score in gender.scores])
        if client is not None and answered_at is not None:
            # The interview first, so the call's final event finds it decided; the room closes after this.
            await interview.finish(inferred)
            report("ended", durationSeconds=round(time.time() - answered_at), transcript=recorder.transcript,
                   reason=reason)
        await asyncio.gather(*reports, return_exceptions=True)
        await http.close()

    ctx.add_shutdown_callback(finish_call)

    survey_agent = SurveyAgent(interview, recorder, hang_up, gender)
    session_started = asyncio.create_task(session.start(agent=survey_agent, room=ctx.room))

    async def warm_backend() -> None:
        # Opens the connection the per-turn backend round trip reuses (the first one paid ~300 ms for TLS).
        try:
            async with http.get(BACKEND_URL + "/api/health", timeout=aiohttp.ClientTimeout(total=5)) as response:
                await response.read()
        except Exception as error:
            logger.warning("backend warm-up failed: %s", error)

    warm_up = asyncio.create_task(warm_backend())
    # While the phone rings: synthesize the replies of a normal call that are not cached yet.
    survey_agent.caching = asyncio.create_task(
        synthesize_missing(session.tts, survey_agent.tts_cache, interview.first_replies()))  # held so it is not collected
    report("ringing")
    try:
        await ctx.api.sip.create_sip_participant(api.CreateSIPParticipantRequest(
            room_name=ctx.room.name,
            sip_trunk_id=trunk_id,
            sip_call_to=phone,
            participant_identity="callee",
            wait_until_answered=True,
        ))
    except api.TwirpError as error:
        code, status = sip_status(error)
        logger.error("call failed: %s sip_status=%s %s", error.message, code, status)
        report(dial_failure_event(code, error.message), sipStatusCode=code, sipStatus=status, reason=error.message)
        ctx.shutdown(reason="call not answered")
        return
    answered_at = time.time()
    report("answered")
    survey_agent.beginning = asyncio.create_task(interview.begin())  # held so it is not collected
    await session_started
    await warm_up

    if AMBIENT_VOLUME > 0:
        background = BackgroundAudioPlayer(ambient_sound=AudioConfig(BuiltinAudioClip.OFFICE_AMBIENCE, volume=AMBIENT_VOLUME))
        await background.start(room=ctx.room, agent_session=session)

    # The callee normally speaks first ("Alo"). If nobody does, say "Alo?" once.
    await asyncio.sleep(3)
    if interview.step == "greeting" and not recorder.turns:
        session.say("Alo?")


if __name__ == "__main__":
    cli.run_app(server)
