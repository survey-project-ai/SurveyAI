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

from common import AGENT_NAME, AMBIENT_VOLUME, BACKEND_URL, LOG_DIR, STT_MODEL, TTS_MODEL, VOICE_ID, load_keys, read_state
from gender import VoiceGenderDetector, load_model
from leveler import VoiceLeveler
from tts_cache import TtsCache

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
    ("candidate", "Cumhurbaşkanı adayı olarak kimi görmek istersiniz? Recep Tayyip Erdoğan, Ekrem İmamoğlu ya da "
     "Mansur Yavaş?", "Recep Tayyip Erdoğan, Ekrem İmamoğlu ve Mansur Yavaş arasından kimi aday olarak görmek istersiniz?"),
]
# Any part of a name counts ("Tayyip", "İmamoğlu'nu"). "Yavaş" alone is also an everyday word ("yavaş
# konuşun"), so it only counts in a short reply. Listed in the order the question reads them out.
CANDIDATES = {
    "Recep Tayyip Erdoğan": ("recep", "tayyip", "erdogan", "rte"),
    "Ekrem İmamoğlu": ("ekrem", "imamoglu", "imam oglu"),
    "Mansur Yavaş": ("mansur",),
}
# Hints for speech recognition (Scribe realtime: up to 50 terms of up to 20 characters). It once wrote
# "Hiçbiri" as "Bir, iki, üç."
KEYTERMS = ["Recep Tayyip Erdoğan", "Erdoğan", "Ekrem İmamoğlu", "İmamoğlu", "Mansur Yavaş", "Hiçbiri",
            "Hiçbirini", "Kararsızım"]
# "İkincisi", "sonuncusu": a choice by the order the names were read out.
ORDINALS = {"birinci": 0, "birincisi": 0, "ilki": 0, "ikinci": 1, "ikincisi": 1, "ucuncu": 2, "ucuncusu": 2,
            "sonuncu": 2, "sonuncusu": 2}
# Replies to "X olarak not alıyorum, doğru mu?"
CONFIRM_YES = ("evet", "dogru", "aynen", "tamam", "oyle", "evet dogru")
CONFIRM_NO = ("hayir", "yanlis", "degil", "oyle degil", "yok")
UNDECIDED = ("kararsizim", "kararsiz", "bilmiyorum", "bilmem", "emin degilim", "fikrim yok", "dusunmedim")
NONE_OF_THEM = ("hicbiri", "hicbirini", "hicbirisi", "hicbire", "hicbirine", "hic biri", "hic birini", "kimseyi",
                "hic kimse")  # Scribe wrote "Eee, hiçbiri" as "hiçbire"
WITHHELD = ("soylemek istemiyorum", "soylemeyeyim", "soylemem", "cevap vermek istemiyorum", "gizli",
            "kalsin", "sizi ilgilendirmez")
# Words that are not an answer to "who", so a reply made only of them is not recorded as another name.
NOT_A_NAME = {"evet", "hayir", "tamam", "peki", "olur", "alo", "efendim", "pardon", "ne", "nasil", "hm", "hmm",
              "aday", "cumhurbaskani", "bilir", "misiniz", "mi",
              # courtesy, once recognised in place of "Hiçbiri" and read back as a name
              "tesekkurler", "tesekkur", "ederim", "sag", "olun", "sagolun", "rica"}
# "Erdoğan'ı istemiyorum", "İmamoğlu olmasın": a name with one of these is not a choice.
NEGATIONS = ("istemiyorum", "istemem", "olmasin", "degil", "asla")
# Trimmed from both ends of another name before it is recorded and read back ("Bence Özgür Özel olsun").
NAME_PADDING = {"olsun", "isterim", "derim", "diyorum", "valla", "vallahi", "bence", "tabii", "tabi", "ee", "eee",
                "yani", "sey", "herhalde", "olabilir", "bey", "hanim", "hayir", "evet"}
# Ending the survey. A bare "istemiyorum" only counts before the questions: during them it is usually
# about the question ("söylemek istemiyorum") or a candidate ("Erdoğan'ı istemiyorum").
STOP_SURVEY = ("rahatsiz etmeyin", "aramayin", "ilgilenmiyorum", "anket istemiyorum", "devam etmek istemiyorum")
# The replies of a call that goes as planned (after the QUESTIONS they build on), synthesized while the phone
# rings if they are not cached yet.
def main_replies() -> list[str]:
    return [OPENING, "Teşekkür ederim. " + QUESTIONS[0][1], *(question for _, question, _ in QUESTIONS[1:]), CLOSING]


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
        # (question code, value) of an answer read back to the callee and waiting for "doğru mu?"
        self.confirming: tuple[str, str] | None = None

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
        if has_any(text, *STOP_SURVEY) or (self.step == "consent" and has_any(text, "istemiyorum")):
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
        if self.confirming is not None:
            confirm_code, value = self.confirming
            self.confirming = None
            if has_any(text, *CONFIRM_NO):
                # "Hayır, hiçbiri" carries the right answer; a bare "Hayır" gets the question again.
                answer = self.interpret(code, raw, text)
                if answer is None:
                    return "Peki. " + self.current_question(), False
                return self._take(code, *answer)
            if has_any(text, *CONFIRM_YES):
                return self._take(confirm_code, value, False)
            # Anything else is taken as a new answer to the question.
        if has_any(text, "neden", "niye", "nicin", "belli degil mi", "ne gerek var"):
            return REASON + " " + self.current_question(), False
        if has_any(text, "tekrar", "anlamadim", "ne dediniz", "efendim", "pardon", "yavas konusur", "yavas konusun",
                           "yavas konusabilir", "yavas soyler", "yavas soyleyin"):
            return self.current_question(full=True), False
        if code == "candidate" and has_any(text, *NEGATIONS) and not has_any(text, *UNDECIDED) and any(
                has_any(text, *keys) for keys in CANDIDATES.values()):
            return "Anladım. Peki " + self.current_question(), False
        answer = self.interpret(code, raw, text)
        if answer is None:
            if presence_check:
                return HEARD + " " + self.current_question(), False
            if previous_reply_missed:
                return self.current_question(), False
            return UNCLEAR + " " + self.current_question(), False
        return self._take(code, *answer)

    def _take(self, code: str, value: str, confirm: bool) -> tuple[str, bool]:
        if confirm:
            # A name not on the list may be misheard (once "Hiçbiri" came out as "Bir, iki, üç"), so the
            # callee hears it back and can correct it before the call moves on.
            self.confirming = (code, value)
            return f"{value.removeprefix('Diğer: ')} olarak not alıyorum, doğru mu?", False
        self.answers[code] = value
        self.question_index += 1
        if self.question_index >= len(self.questions):
            self.step = "done"
            return CLOSING, True
        return self.current_question(), False

    @staticmethod
    def interpret(code: str, raw: str, text: str) -> tuple[str, bool] | None:
        """(value to record, whether to read it back for confirmation), or None when it is not an answer."""
        if code == "age":
            number = spoken_number(text)
            return None if number is None else (str(number), False)
        if code == "candidate":
            return candidate_answer(raw, text)
        return None


def candidate_answer(raw: str, text: str) -> tuple[str, bool] | None:
    words = text.split()
    named = {name for name, keys in CANDIDATES.items() if has_any(text, *keys)}
    if "yavas" in words and len(words) <= 3:
        named.add("Mansur Yavaş")
    if has_any(text, *UNDECIDED):
        # "Kararsızım ama muhtemelen İmamoğlu ya da Yavaş": undecided, leaning towards the names given.
        # "Kararsızım ama Erdoğan değil" excludes a name rather than leaning towards it.
        leaning = [] if has_any(text, *NEGATIONS) else [name for name in CANDIDATES if name in named]
        return ("Kararsız (" + " / ".join(leaning) + ")" if leaning else "Kararsız"), False
    if len(named) == 1:
        return named.pop(), False
    if len(named) > 1:
        return None  # "Erdoğan değil, İmamoğlu": ask again rather than guess
    picked = {ORDINALS[word] for word in words if word in ORDINALS}
    if len(picked) == 1:
        return list(CANDIDATES)[picked.pop()], False
    if has_any(text, *WITHHELD):
        return "Cevap vermek istemiyor", False
    if has_any(text, *NONE_OF_THEM):
        return "Hiçbiri", False
    # Numbers are not a name. A bare "üç" might mean the third name, but the question is asked again
    # rather than guessed.
    if spoken_number(text) is not None:
        return None
    if 1 <= len(words) <= 5 and not set(words) <= NOT_A_NAME and not raw.rstrip().endswith("?"):
        name = other_name(raw)
        if name:
            return f"Diğer: {name}", True  # someone not on the list, read back for confirmation
    return None


def other_name(raw: str) -> str:
    """'Bence Özgür Özel'i olsun.' -> 'Özgür Özel'"""
    words = raw.strip(" .,!?").replace(",", " ").split()
    while words and normalize(words[0]) in NAME_PADDING:
        words.pop(0)
    while words and normalize(words[-1]) in NAME_PADDING:
        words.pop()
    if words and "'" in words[-1]:
        words[-1] = words[-1].split("'")[0]  # case suffix: Özel'i, Davutoğlu'nu
    return " ".join(words)


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

    def user_state(self, new_state: str, at: float) -> None:
        if new_state == "listening":
            self.user_stopped_at = at

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
    def __init__(self, flow: SurveyFlow, recorder: TurnRecorder, http: aiohttp.ClientSession, hang_up,
                 gender: VoiceGenderDetector) -> None:
        super().__init__(instructions="Fixed-script survey prototype; replies come from SurveyFlow.")
        self.flow, self.recorder, self.http, self.hang_up = flow, recorder, http, hang_up
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
        if (self.last_reply is not None and not self.last_reply.done() and normalize(user_text) in FILLERS
                and self.flow.confirming is None):
            logger.info("ignoring %r: the previous reply has not been played yet", user_text)
            raise StopResponse()
        if self.flow.step != "greeting" and is_hesitation(user_text):
            logger.info("ignoring %r: the callee is thinking", user_text)
            raise StopResponse()
        turn = self.recorder.start_turn(self.flow.step, user_text, self.last_voice_at)
        turn["heard_at_s"] = self.heard_offset()
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
        survey_agent.save_audio(ctx.room.name)
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
    # While the phone rings: synthesize the replies of a normal call that are not cached yet.
    survey_agent.caching = asyncio.create_task(
        synthesize_missing(session.tts, survey_agent.tts_cache, main_replies()))  # held so it is not collected
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
