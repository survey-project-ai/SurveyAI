"""The same survey call with an OpenAI speech-to-speech model instead of the scripted pipeline in agent.py.

One model hears the callee and speaks the replies (gpt-realtime-2.1 or its mini variant, through LiveKit
Inference, so no OpenAI key is needed). The survey is given to it as instructions, and it records answers
with a tool. Everything around the model matches agent.py so the two can be compared: the Netgsm trunk,
the office ambience, the voice leveler, gender from the callee's voice, both sides recorded to logs/,
and latency measured from the last loud frame of the callee's audio to the agent's first audio.

Run: python agent_realtime.py dev, then python call.py <number> openai/gpt-realtime-2.1-mini
"""
from __future__ import annotations

import asyncio
import faulthandler
import json
import logging
import time

import numpy as np
from livekit import api, rtc
from livekit.agents import (
    Agent,
    AgentServer,
    AgentSession,
    AudioConfig,
    BackgroundAudioPlayer,
    BuiltinAudioClip,
    JobContext,
    JobProcess,
    RunContext,
    cli,
    function_tool,
    inference,
)

from common import AMBIENT_VOLUME, LOG_DIR, load_keys, read_state
from gender import VoiceGenderDetector, load_model
from leveler import VoiceLeveler

load_keys()
logger = logging.getLogger("survey-realtime")
faulthandler.enable()

AGENT_NAME = "survey-realtime"
DEFAULT_MODEL = "openai/gpt-realtime-2.1-mini"

OPENING = "Merhaba, ben Ayna Araştırma adına kısa bir anket için arıyorum. Size birkaç soru sorabilir miyim?"
AGE_QUESTION = "Kaç yaşındasınız?"
CANDIDATE_QUESTION = ("Cumhurbaşkanı adayı olarak kimi görmek istersiniz? Recep Tayyip Erdoğan, Ekrem İmamoğlu ya da "
                      "Mansur Yavaş?")
CLOSING = "Vakit ayırdığınız için teşekkür ederim. Anketimiz burada sona erdi. İyi günler dilerim."

INSTRUCTIONS = f"""Sen Ayna Araştırma adına telefonla anket yapan bir anketörsün. Türkçe konuş; doğal, sıcak ve
kısa cümlelerle, deneyimli bir insan anketör gibi.

Görüşmenin akışı:
1. Karşındaki kişi telefonu açıp konuştuğunda (genelde "Alo") aynen şunu söyle: "{OPENING}"
2. Kabul ederse "Teşekkür ederim." de ve aynen şunu sor: "{AGE_QUESTION}". Kabul etmezse kibarca teşekkür
   edip vedalaş ve gorusmeyi_bitir aracını çağır.
3. Yaşını söyleyince cevap_kaydet(soru="yas", cevap=<yaş, rakamla>) aracını çağır, sonra aynen şunu sor:
   "{CANDIDATE_QUESTION}"
4. Cevabı cevap_kaydet(soru="aday", cevap=...) ile kaydet:
   - İsmin bir kısmı yeterli: "Tayyip" ya da "Erdoğan" -> "Recep Tayyip Erdoğan"; "Ekrem" -> "Ekrem İmamoğlu";
     "Mansur" ya da "Yavaş" -> "Mansur Yavaş".
   - Listede olmayan biri söylenirse ismi tekrar edip doğru anladığını teyit et, sonra "Diğer: <isim>" kaydet.
   - Kararsızsa "Kararsız" kaydet; aralarında kaldığı isimleri söylediyse "Kararsız (<isimler>)".
   - Hiçbirini istemiyorsa "Hiçbiri", söylemek istemiyorsa "Cevap vermek istemiyor" kaydet.
5. Sonra aynen şunu söyle: "{CLOSING}" ve gorusmeyi_bitir aracını çağır.

Kurallar:
- Soruları yazıldığı gibi sor. Kendi görüşünü söyleme, adayları yorumlama, kişiyi yönlendirme.
- Kişi düşünürken ("eee", "hmm") bekle, sözünü kesme.
- Anlamadığın bir cevapta soruyu kısaca tekrar sor. Kim olduğunu sorarlarsa Ayna Araştırma adına anket
  yaptığını söyle. Neden sorduğunu sorarlarsa sorunun ankete katılan herkese sorulduğunu söyle.
- Cinsiyet sorma.
- Araç çağırdığını ya da kayıt yaptığını söyleme.
"""


class RealtimeSurveyAgent(Agent):
    def __init__(self, answers: dict[str, str], hang_up) -> None:
        super().__init__(instructions=INSTRUCTIONS)
        self.answers, self.hang_up = answers, hang_up
        self.leveler = VoiceLeveler()
        self.sent_audio: list[bytes] = []
        self.sent_sample_rate = 24000

    @function_tool
    async def cevap_kaydet(self, context: RunContext, soru: str, cevap: str) -> str:
        """Bir anket cevabını kaydeder.

        Args:
            soru: "yas" ya da "aday".
            cevap: kaydedilecek değer.
        """
        self.answers[soru] = cevap
        logger.info("answer %s = %r", soru, cevap)
        return "kaydedildi"

    @function_tool
    async def gorusmeyi_bitir(self, context: RunContext) -> str:
        """Kapanış ya da veda cümlesini söyledikten sonra görüşmeyi sonlandırır."""
        asyncio.create_task(self.hang_up())
        return "görüşme sonlandırılıyor"

    async def realtime_audio_output_node(self, audio, model_settings):
        # Same leveler and recording as agent.py's replies.
        async for frame in Agent.default.realtime_audio_output_node(self, audio, model_settings):
            levelled = self.leveler.process_frame(frame)
            self.sent_sample_rate = levelled.sample_rate
            self.sent_audio.append(bytes(levelled.data))
            yield levelled


class CalleeAudio:
    """The callee's audio as it arrives: recorded, fed to the gender detector, and the time of the last
    loud frame kept for the latency measurement."""

    def __init__(self, gender: VoiceGenderDetector) -> None:
        self.gender = gender
        self.chunks: list[bytes] = []
        self.sample_rate = 16000
        self.peak = 0.0
        self.last_voice_at: float | None = None

    async def consume(self, stream: rtc.AudioStream) -> None:
        async for event in stream:
            frame = event.frame
            self.sample_rate = frame.sample_rate
            samples = np.frombuffer(frame.data, dtype=np.int16)
            self.chunks.append(samples.tobytes())
            self.gender.push_frame(frame)
            rms = float(np.sqrt(np.mean((samples.astype(np.float32) / 32768) ** 2))) if len(samples) else 0.0
            self.peak = max(self.peak, rms)
            if rms > max(0.003, 0.1 * self.peak):
                self.last_voice_at = time.time()


def write_wav(path, chunks: list[bytes], sample_rate: int) -> None:
    import wave

    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(sample_rate)
        handle.writeframes(b"".join(chunks))


def prewarm(proc: JobProcess) -> None:
    proc.userdata["gender_model"] = load_model()


server = AgentServer(setup_fnc=prewarm, num_idle_processes=1, initialize_process_timeout=60.0)


@server.rtc_session(agent_name=AGENT_NAME)
async def entrypoint(ctx: JobContext) -> None:
    metadata = json.loads(ctx.job.metadata or "{}")
    phone, model = metadata["phone"], metadata.get("model", DEFAULT_MODEL)
    trunk_id = read_state()["sip_trunk_id"]
    LOG_DIR.mkdir(exist_ok=True)
    log_path = LOG_DIR / f"{ctx.room.name}.jsonl"
    answers: dict[str, str] = {}
    gender = VoiceGenderDetector(ctx.proc.userdata["gender_model"])
    callee = CalleeAudio(gender)
    turns: list[dict] = []
    last_user_text = {"text": None}
    speaking_since = {"at": None}
    await ctx.connect()
    logger.info("call with %s", model)

    session = AgentSession(llm=inference.RealtimeModel(model=model))

    async def hang_up() -> None:
        # Let the closing sentence finish playing first.
        await asyncio.sleep(0.5)
        for _ in range(60):
            if session.agent_state != "speaking":
                break
            await asyncio.sleep(0.25)
        await asyncio.sleep(0.5)
        try:
            await ctx.api.room.remove_participant(api.RoomParticipantIdentity(room=ctx.room.name, identity="callee"))
        except api.TwirpError as error:
            logger.info("callee already gone: %s", error.message)
        ctx.shutdown(reason="survey finished")

    survey_agent = RealtimeSurveyAgent(answers, hang_up)

    @session.on("user_input_transcribed")
    def _transcribed(event) -> None:
        if event.is_final:
            last_user_text["text"] = event.transcript
            logger.info("callee said %r", event.transcript)

    @session.on("user_state_changed")
    def _user_state(event) -> None:
        if event.old_state == "speaking" and event.new_state != "speaking":
            gender.end_turn()

    @session.on("agent_state_changed")
    def _agent_state(event) -> None:
        if event.new_state == "speaking":
            speaking_since["at"] = event.created_at
            voice_at = callee.last_voice_at
            # Only a reply to speech that came after the agent's previous turn counts as a turn.
            previous_end = turns[-1]["agent_ended_at"] if turns and turns[-1].get("agent_ended_at") else 0
            if voice_at and voice_at > previous_end:
                turns.append({"user_text": last_user_text["text"], "latency": round(event.created_at - voice_at, 3)})
                logger.info("turn %s", turns[-1])
        elif event.old_state == "speaking" and turns:
            turns[-1]["agent_ended_at"] = event.created_at

    @session.on("conversation_item_added")
    def _item(event) -> None:
        item = event.item
        if getattr(item, "role", None) in ("user", "assistant"):
            logger.info("message %s: %r", item.role, item.text_content)

    async def finish_call() -> None:
        decision = await gender.decide(timeout=2.0)
        answers["gender"] = f"{decision.label or 'belirsiz'} (sesten)"
        with log_path.open("w", encoding="utf-8") as handle:
            for turn in turns:
                turn.pop("agent_ended_at", None)
                handle.write(json.dumps(turn, ensure_ascii=False) + "\n")
        write_wav(LOG_DIR / f"{ctx.room.name}.agent.wav", survey_agent.sent_audio, survey_agent.sent_sample_rate)
        write_wav(LOG_DIR / f"{ctx.room.name}.callee.wav", callee.chunks, callee.sample_rate)
        latencies = [turn["latency"] for turn in turns]
        logger.info("call finished (%s): %d turns, latencies=%s, answers=%s, gender=%s", model, len(turns),
                    latencies, answers, decision)

    ctx.add_shutdown_callback(finish_call)

    session_started = asyncio.create_task(session.start(agent=survey_agent, room=ctx.room))
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

    participant = await ctx.wait_for_participant(identity="callee")
    stream = rtc.AudioStream.from_participant(participant=participant, track_source=rtc.TrackSource.SOURCE_MICROPHONE,
                                              sample_rate=16000, num_channels=1)
    survey_agent.callee_task = asyncio.create_task(callee.consume(stream))  # held so it is not collected

    if AMBIENT_VOLUME > 0:
        background = BackgroundAudioPlayer(ambient_sound=AudioConfig(BuiltinAudioClip.OFFICE_AMBIENCE, volume=AMBIENT_VOLUME))
        await background.start(room=ctx.room, agent_session=session)

    # The callee normally speaks first ("Alo"). If nobody does, say "Alo?" once.
    await asyncio.sleep(3)
    if not turns and speaking_since["at"] is None:
        session.generate_reply(instructions='Karşı taraf henüz konuşmadı. Sadece "Alo?" de ve bekle.')


if __name__ == "__main__":
    cli.run_app(server)
