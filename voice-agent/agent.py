"""Phase 0 voice agent: places one outbound call through the Netgsm trunk and runs a fixed three-question
survey without an LLM. The prompt for each turn is spoken directly, which is the point of the
prototype: no second LLM pass per turn. Every turn's timings are written to logs/<room>.jsonl.

Run the worker:   .venv/Scripts/python agent.py dev
Place a call:     .venv/Scripts/python call.py +905xxxxxxxxx
"""
import asyncio
import faulthandler
import json
import logging
import re
import time
import unicodedata
import wave

import aiohttp
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
from livekit.plugins import elevenlabs, silero

from common import AGENT_NAME, AMBIENT_VOLUME, BACKEND_URL, LOG_DIR, STT_MODEL, TTS_MODEL, VOICE_ID, load_keys, read_state
from gender import VoiceGenderDetector, load_model
from leveler import VoiceLeveler

load_keys()
logger = logging.getLogger("survey-prototype")
# A native crash (the worker once aborted with no log while a call ended) then at least prints every
# thread's Python stack to the log.
faulthandler.enable()

OPENING = "Merhaba, ben Ayna Araştırma adına kısa bir anket için arıyorum. Size birkaç soru sorabilir miyim?"
IDENTITY = "Ben Ayna Araştırma adına kısa bir anket için arıyorum."
REASON = "Bu soruyu anket gereği tüm katılımcılara soruyoruz. Cevap vermek istemezseniz bunu da söyleyebilirsiniz."
UNCLEAR = "Sizi net anlayamadım."
HEARD = "Evet, sizi duyuyorum."
CONSENT_QUESTION = "Size birkaç soru sorabilir miyim?"
SILENCE = "Sizi duyamadım."
CLOSING = "Vakit ayırdığınız için teşekkür ederim. Anketimiz burada sona erdi. İyi günler dilerim."
DECLINED = "Anladım, teşekkür ederim. İyi günler dilerim."
# (code, question, shorter form used when the question has to be asked again). Gender is not asked: it is
# noted from the callee's voice over the whole call (gender.py).
QUESTIONS = [
    ("age", "Kaç yaşındasınız?", "Kaç yaşındasınız?"),
    ("rating", "Belediye hizmetlerinden ne kadar memnunsunuz? Bir ile beş arasında bir puan verir misiniz?",
     "Bir ile beş arasında kaç puan verirsiniz?"),
]
# Short sounds that arrive before the agent's reply has been played cannot answer the next question; the
# recognizer also produced a phantom "Evet." from near-silence in the first test call.
FILLERS = {"", "evet", "tamam", "hi", "hm", "hmm", "ee", "eee", "aha", "alo", "efendim", "he", "hi hi"}
# "Eee", "hmm", "şey": the callee is thinking. Answering it cut in on a callee who was about to give a rating.
HESITATION = re.compile(r"(e+|i+|m+|h+m+|a+h+|sey|yani)( (e+|i+|m+|h+m+|a+h+|sey|yani))*")
# The callee checking whether the line works, usually because they did not hear the agent.
PRESENCE_CHECKS = ("alo", "duyuyor musunuz", "orada misiniz", "sesim geliyor mu", "sesimi duyuyor musunuz")
NUMBER_WORDS = {
    "bir": 1, "iki": 2, "uc": 3, "dort": 4, "bes": 5, "alti": 6, "yedi": 7, "sekiz": 8, "dokuz": 9,
    "on": 10, "yirmi": 20, "otuz": 30, "kirk": 40, "elli": 50, "altmis": 60, "yetmis": 70, "seksen": 80, "doksan": 90,
}


def normalize(text: str) -> str:
    folded = text.lower().translate(str.maketrans("ıİşŞğĞüÜöÖçÇ", "iissgguuoocc"))
    folded = unicodedata.normalize("NFD", folded)
    folded = "".join(ch for ch in folded if unicodedata.category(ch) != "Mn")
    return re.sub(r"[^a-z0-9 ]+", " ", folded).strip()


def is_hesitation(text: str) -> bool:
    return HESITATION.fullmatch(" ".join(normalize(text).split())) is not None


def has_any(text: str, *phrases: str) -> bool:
    padded = f" {text} "
    return any(f" {phrase} " in padded for phrase in phrases)


def spoken_number(text: str) -> int | None:
    digits = re.search(r"\d+", text)
    if digits:
        return int(digits.group())
    total, found = 0, False
    for token in text.split():
        if token in NUMBER_WORDS:
            total, found = total + NUMBER_WORDS[token], True
    return total if found else None


class SurveyFlow:
    """A tiny stand-in for the backend interview engine; enough to drive realistic turns."""

    def __init__(self) -> None:
        self.step = "greeting"
        self.questions = list(QUESTIONS)
        self.question_index = 0
        self.asked: set[str] = set()
        self.answers: dict[str, str] = {}

    def current_question(self, full: bool = False) -> str:
        """The question in full the first time (or when asked to repeat it), the short form after that."""
        code, question, short = self.questions[self.question_index]
        if full or code not in self.asked:
            self.asked.add(code)
            return question
        return short

    def handle(self, raw: str, previous_reply_missed: bool = False) -> tuple[str, bool]:
        """previous_reply_missed: the agent's last reply was cut off before the callee heard it."""
        text = normalize(raw)
        presence_check = has_any(text, *PRESENCE_CHECKS)
        if self.step == "greeting":
            self.step = "consent"
            return OPENING, False
        if has_any(text, "istemiyorum", "rahatsiz etmeyin", "aramayin", "ilgilenmiyorum"):
            self.step = "done"
            return DECLINED, True
        if has_any(text, "kimsiniz", "kim ariyor", "nereden ariyorsunuz", "siz kimsiniz"):
            return IDENTITY + " " + (CONSENT_QUESTION if self.step == "consent" else self.current_question()), False
        if self.step == "consent":
            if has_any(text, "evet", "olur", "tabii", "tabi", "buyurun", "buyrun", "tamam", "sorabilirsiniz", "sorun degil", "dinliyorum"):
                self.step = "questions"
                return "Teşekkür ederim. " + self.current_question(), False
            ask = OPENING if previous_reply_missed else CONSENT_QUESTION
            if presence_check:
                return HEARD + " " + ask, False
            if previous_reply_missed:
                return OPENING, False
            return "Kısaca onayınızı rica edeceğim. " + CONSENT_QUESTION, False
        code = self.questions[self.question_index][0]
        if has_any(text, "neden", "niye", "nicin", "belli degil mi", "ne gerek var"):
            return REASON + " " + self.current_question(), False
        if has_any(text, "tekrar", "anlamadim", "ne dediniz", "efendim"):
            return self.current_question(full=True), False
        if not self.accepts(code, text):
            if presence_check:
                return HEARD + " " + self.current_question(), False
            if previous_reply_missed:
                return self.current_question(), False
            return UNCLEAR + " " + self.current_question(), False
        self.answers[code] = raw
        self.question_index += 1
        if self.question_index >= len(self.questions):
            self.step = "done"
            return CLOSING, True
        return self.current_question(), False

    @staticmethod
    def accepts(code: str, text: str) -> bool:
        if code == "age":
            return spoken_number(text) is not None
        number = spoken_number(text)
        return number is not None and 1 <= number <= 5


class TurnRecorder:
    """Collects per-turn timings: callee stops speaking -> agent audio starts."""

    def __init__(self, room_name: str) -> None:
        LOG_DIR.mkdir(exist_ok=True)
        self.path = LOG_DIR / f"{room_name}.jsonl"
        self.user_stopped_at: float | None = None
        self.pending: dict | None = None
        self.turns: list[dict] = []

    def user_state(self, new_state: str, at: float) -> None:
        if new_state == "listening":
            self.user_stopped_at = at

    def start_turn(self, step: str, user_text: str) -> dict:
        self.pending = {"step": step, "user_text": user_text, "user_stopped_at": self.user_stopped_at}
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
        entry = {"role": role, "text": getattr(item, "text_content", None)}
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
        with (self.path.parent / (self.path.stem + ".messages.jsonl")).open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(entry, ensure_ascii=False) + "\n")
        logger.info("message %s", entry)

    def agent_speaking(self, at: float) -> None:
        turn, self.pending = self.pending, None
        if turn is None:
            return
        if turn.get("user_stopped_at"):
            turn["latency"] = round(at - turn["user_stopped_at"], 3)
        turn.pop("user_stopped_at", None)
        self.turns.append(turn)
        with self.path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(turn, ensure_ascii=False) + "\n")
        logger.info("turn %s", turn)


class SurveyAgent(Agent):
    def __init__(self, flow: SurveyFlow, recorder: TurnRecorder, http: aiohttp.ClientSession, hang_up,
                 gender: VoiceGenderDetector) -> None:
        super().__init__(instructions="Fixed-script survey prototype; replies come from SurveyFlow.")
        self.flow, self.recorder, self.http, self.hang_up = flow, recorder, http, hang_up
        self.gender = gender
        self.leveler = VoiceLeveler()
        # What the agent sent to the line, one reply after another, written to logs/ when the call ends.
        self.sent_audio: list[bytes] = []
        self.sent_sample_rate = 16000
        self.last_reply = None

    async def stt_node(self, audio, model_settings):
        # The callee's audio also goes to the gender detector.
        async def tee():
            async for frame in audio:
                self.gender.push_frame(frame)
                yield frame

        async for event in Agent.default.stt_node(self, tee(), model_settings):
            yield event

    def _last_reply_missed(self) -> bool:
        """True when the previous reply was interrupted before most of it was played."""
        speech = self.last_reply
        if speech is None or not speech.interrupted:
            return False
        said = getattr(speech, "_reply_text", "")
        heard = " ".join(item.text_content or "" for item in speech.chat_items if getattr(item, "role", None) == "assistant")
        return len(heard) < 0.6 * len(said)

    async def tts_node(self, text, model_settings):
        # One leveler for the whole call, so each reply starts at the gain the previous one ended with.
        async for frame in Agent.default.tts_node(self, text, model_settings):
            levelled = self.leveler.process_frame(frame)
            self.sent_sample_rate = levelled.sample_rate
            self.sent_audio.append(bytes(levelled.data))
            yield levelled
        self.sent_audio.append(bytes(int(self.sent_sample_rate * 0.5) * 2))  # half a second between replies

    def save_sent_audio(self, path) -> None:
        with wave.open(str(path), "wb") as handle:
            handle.setnchannels(1)
            handle.setsampwidth(2)
            handle.setframerate(self.sent_sample_rate)
            handle.writeframes(b"".join(self.sent_audio))

    async def on_user_turn_completed(self, turn_ctx, new_message) -> None:
        user_text = new_message.text_content or ""
        if self.last_reply is not None and not self.last_reply.done() and normalize(user_text) in FILLERS:
            logger.info("ignoring %r: the previous reply has not been played yet", user_text)
            raise StopResponse()
        if self.flow.step != "greeting" and is_hesitation(user_text):
            logger.info("ignoring %r: the callee is thinking", user_text)
            raise StopResponse()
        turn = self.recorder.start_turn(self.flow.step, user_text)
        started = time.time()
        # Stands in for the backend /answer round trip: same host, same network path.
        backend_started = time.time()
        try:
            async with self.http.get(BACKEND_URL + "/api/health", timeout=aiohttp.ClientTimeout(total=5)) as response:
                await response.read()
        except Exception as error:  # the measurement must not break the call
            logger.warning("backend probe failed: %s", error)
        turn["backend_ms"] = round((time.time() - backend_started) * 1000)
        # Scores this turn's voice in the background; the gender is decided when the call ends.
        self.gender.end_turn()
        missed = self._last_reply_missed()
        if missed:
            turn["previous_reply_missed"] = True
        reply, end_call = self.flow.handle(user_text, previous_reply_missed=missed)
        turn["handler_ms"] = round((time.time() - started) * 1000)
        turn["reply"] = reply
        # The opening is said in full even if the callee says "Alo?" again over it, as a person would; in the
        # second test call a repeated "Alo" cancelled it before it started and the callee never heard it.
        speech = self.session.say(reply, allow_interruptions=reply != OPENING)
        speech._reply_text = reply
        self.last_reply = speech
        if end_call:
            asyncio.create_task(self.hang_up(speech))
        raise StopResponse()


def prewarm(proc: JobProcess) -> None:
    proc.userdata["vad"] = silero.VAD.load()
    proc.userdata["gender_model"] = load_model()


# One process is kept ready so the call does not wait for process start-up.
server = AgentServer(setup_fnc=prewarm, num_idle_processes=1, initialize_process_timeout=60.0)


@server.rtc_session(agent_name=AGENT_NAME)
async def entrypoint(ctx: JobContext) -> None:
    metadata = json.loads(ctx.job.metadata or "{}")
    phone = metadata["phone"]
    trunk_id = read_state()["sip_trunk_id"]
    await ctx.connect()

    flow = SurveyFlow()
    recorder = TurnRecorder(ctx.room.name)
    gender = VoiceGenderDetector(ctx.proc.userdata["gender_model"])
    http = aiohttp.ClientSession()
    session = AgentSession(
        vad=ctx.proc.userdata["vad"],
        # Scribe realtime only finalizes a transcript on a commit; with server VAD it commits after
        # 0.5 s of silence, and that commit ends the user's turn.
        stt=elevenlabs.STT(model=STT_MODEL, language_code="tr",
                           server_vad=elevenlabs.stt.VADOptions(vad_silence_threshold_secs=0.5)),
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
        turn_detection="stt",
        # A short noise must not cut the agent off; if no real speech follows, the agent resumes.
        min_interruption_duration=0.6,
        resume_false_interruption=True,
    )

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

    async def finish_call() -> None:
        await http.close()
        survey_agent.save_sent_audio(LOG_DIR / f"{ctx.room.name}.agent.wav")
        decision = await gender.decide(timeout=2.0)
        flow.answers["gender"] = f"{decision.label or 'belirsiz'} (sesten)"
        logger.info("call finished: %d turns, answers=%s, gender=%s, scores=%s", len(recorder.turns), flow.answers,
                    decision, [score.__dict__ for score in gender.scores])

    ctx.add_shutdown_callback(finish_call)

    survey_agent = SurveyAgent(flow, recorder, http, hang_up, gender)
    session_started = asyncio.create_task(session.start(agent=survey_agent, room=ctx.room))

    async def warm_backend() -> None:
        # Opens the connection the per-turn backend round trip reuses (the first one paid ~300 ms for TLS).
        try:
            async with http.get(BACKEND_URL + "/api/health", timeout=aiohttp.ClientTimeout(total=5)) as response:
                await response.read()
        except Exception as error:
            logger.warning("backend warm-up failed: %s", error)

    warm_up = asyncio.create_task(warm_backend())
    try:
        await ctx.api.sip.create_sip_participant(api.CreateSIPParticipantRequest(
            room_name=ctx.room.name,
            sip_trunk_id=trunk_id,
            sip_call_to=phone,
            participant_identity="callee",
            wait_until_answered=True,
        ))
    except api.TwirpError as error:
        logger.error("call failed: %s sip_status=%s %s", error.message,
                     error.metadata.get("sip_status_code"), error.metadata.get("sip_status"))
        ctx.shutdown()
        return
    await session_started
    await warm_up

    if AMBIENT_VOLUME > 0:
        background = BackgroundAudioPlayer(ambient_sound=AudioConfig(BuiltinAudioClip.OFFICE_AMBIENCE, volume=AMBIENT_VOLUME))
        await background.start(room=ctx.room, agent_session=session)

    # The callee normally speaks first ("Alo"). If nobody does, say "Alo?" once.
    await asyncio.sleep(3)
    if flow.step == "greeting" and not recorder.turns:
        session.say("Alo?")


if __name__ == "__main__":
    cli.run_app(server)
