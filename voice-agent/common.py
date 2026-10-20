"""Shared settings for the voice agent.

Secrets come from environment variables (LIVEKIT_URL, LIVEKIT_API_KEY, LIVEKIT_API_SECRET, ELEVEN_API_KEY,
LIVEKIT_TOOL_API_SECRET, LIVEKIT_WEBHOOK_SECRET). On the development machine they may come from a local env
file outside the repository instead (SURVEYAI_KEYS_FILE, default ~/Desktop/elevenlabs-keys.env). They are
never printed.
"""
import json
import os
import socket
from pathlib import Path

# On the development machine the IPv6 DNS servers do not answer, so every IPv4+IPv6 lookup waits about
# 12 seconds (IPv4-only lookups take 0.1 s), and names are resolved IPv4 only there. Not on Railway, whose
# private network is IPv6; SURVEYAI_FORCE_IPV4 (1/0) overrides.
_getaddrinfo = socket.getaddrinfo


def _ipv4_getaddrinfo(host, port, family=0, type=0, proto=0, flags=0):
    return _getaddrinfo(host, port, socket.AF_INET if family == socket.AF_UNSPEC else family, type, proto, flags)


if os.environ.get("SURVEYAI_FORCE_IPV4", "0" if "RAILWAY_ENVIRONMENT" in os.environ else "1") == "1":
    socket.getaddrinfo = _ipv4_getaddrinfo

HERE = Path(__file__).resolve().parent
STATE_FILE = HERE / ".state.json"
LOG_DIR = HERE / "logs"

# The name the worker registers under, which the backend dispatches to (its LIVEKIT_AGENT_NAME). The
# deployed agent is "survey-agent"; a worker started without the variable registers as the development
# agent, so it never takes calls meant for the deployed one.
AGENT_NAME = os.environ.get("LIVEKIT_AGENT_NAME", "survey-agent-dev")
CALLER_NUMBER = "+903229110347"
SIP_ADDRESS = "sip.netgsm.com.tr"
# Gökçe Deniz (a library voice; needs API credits), picked by the user from 8 voices reading the survey
# lines. Before: Duygu Eris (MzfWWOj9g3sIKex1YFMV), and Gözde Arıkan, the ElevenLabs agent's voice, which
# swung its pitch a lot within sentences. PROTOTYPE_VOICE_ID overrides it.
VOICE_ID = os.environ.get("PROTOTYPE_VOICE_ID", "oPC5I9GKjMReiaM29gjY")
# v4 Turbo sounded more natural than Flash v2.5 in a listening test and started faster (0.34 s vs 0.66 s).
TTS_MODEL = os.environ.get("PROTOTYPE_TTS_MODEL", "eleven_v4_turbo")
# Office ambience under the call, 0 turns it off; the player multiplies and clips, so values above 1 work.
# The level picked in a listening test sits about 18 dB under the voice; 4.2 keeps that under the voice
# as levelled by leveler.py.
AMBIENT_VOLUME = float(os.environ.get("PROTOTYPE_AMBIENT_VOLUME", "4.2"))
# Speech recognition. Speechmatics Linden-1 (through LiveKit Inference) got all 15 key answers right in
# the recorded test calls of 5 callers; ElevenLabs Scribe v2 realtime, used before, got 13 ("51" -> "55",
# "Hiçbiri" -> "Teşekkürler"). "scribe_v2_realtime" switches back.
STT_MODEL = os.environ.get("PROTOTYPE_STT_MODEL", "speechmatics/linden-1")
# The SurveyAI backend: the interview engine and the call events of calls it placed.
BACKEND_URL = os.environ.get("SURVEYAI_API_URL", "https://surveyai-backend-production.up.railway.app").rstrip("/")


def backend_settings():
    """The backend's address and the secrets it shares with the agent (the same variables the backend reads)."""
    from backend import BackendSettings

    tool_secret = os.environ.get("LIVEKIT_TOOL_API_SECRET", "")
    webhook_secret = os.environ.get("LIVEKIT_WEBHOOK_SECRET", "")
    if not tool_secret or not webhook_secret:
        raise RuntimeError("LIVEKIT_TOOL_API_SECRET and LIVEKIT_WEBHOOK_SECRET are needed for calls the backend places")
    return BackendSettings(BACKEND_URL, tool_secret, webhook_secret)


def load_keys() -> dict[str, str]:
    """The local keys file's values, also put into the environment where it does not set them already.
    Without the file (on Railway) the environment alone is used."""
    path = Path(os.environ.get("SURVEYAI_KEYS_FILE", Path.home() / "Desktop" / "elevenlabs-keys.env"))
    values: dict[str, str] = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                name, value = line.split("=", 1)
                values[name.strip()] = value.strip().strip('"').strip("'")
    # The LiveKit SDK and the ElevenLabs plugin read these environment variables.
    for variable, key in (("LIVEKIT_URL", "LIVEKIT_URL"), ("LIVEKIT_API_KEY", "LIVEKIT_API_KEY"),
                          ("LIVEKIT_API_SECRET", "LIVEKIT_API_SECRET"), ("ELEVEN_API_KEY", "NEW_ELEVENLABS_API_KEY")):
        if key in values:
            os.environ.setdefault(variable, values[key])
    return values


def read_state() -> dict:
    return json.loads(STATE_FILE.read_text(encoding="utf-8")) if STATE_FILE.exists() else {}


def write_state(state: dict) -> None:
    STATE_FILE.write_text(json.dumps(state, indent=2), encoding="utf-8")
