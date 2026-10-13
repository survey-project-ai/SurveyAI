"""Shared settings for the voice pipeline prototype (Phase 0 of docs/voice-pipeline-prototype-plan.md).

Secrets are read from a local env file outside the repository (SURVEYAI_KEYS_FILE, default
~/Desktop/elevenlabs-keys.env) and are never printed.
"""
import json
import os
import socket
from pathlib import Path

# On the development machine the IPv6 DNS servers do not answer, so every IPv4+IPv6 lookup waits about
# 12 seconds (IPv4-only lookups take 0.1 s). The prototype resolves IPv4 only; a server in the EU does
# not need this.
_getaddrinfo = socket.getaddrinfo


def _ipv4_getaddrinfo(host, port, family=0, type=0, proto=0, flags=0):
    return _getaddrinfo(host, port, socket.AF_INET if family == socket.AF_UNSPEC else family, type, proto, flags)


socket.getaddrinfo = _ipv4_getaddrinfo

HERE = Path(__file__).resolve().parent
STATE_FILE = HERE / ".state.json"
LOG_DIR = HERE / "logs"

AGENT_NAME = "survey-prototype"
CALLER_NUMBER = "+903229110347"
SIP_ADDRESS = "sip.netgsm.com.tr"
# Same voice (Gözde Arıkan, a library voice; needs API credits) and TTS model as the ElevenLabs agent,
# so the comparison only changes the architecture. PROTOTYPE_VOICE_ID overrides it.
VOICE_ID = os.environ.get("PROTOTYPE_VOICE_ID", "aEJD8mYP0nuof1XHShVY")
# v4 Turbo sounded more natural than Flash v2.5 in a listening test and started faster (0.34 s vs 0.66 s).
TTS_MODEL = os.environ.get("PROTOTYPE_TTS_MODEL", "eleven_v4_turbo")
# Office ambience under the call, 0 turns it off. The built-in clip is quiet (about 29 dB below the voice
# at 1.0); the player multiplies and clips, so values above 1 work. 2.0 was picked in a listening test.
AMBIENT_VOLUME = float(os.environ.get("PROTOTYPE_AMBIENT_VOLUME", "2.0"))
STT_MODEL = "scribe_v2_realtime"
BACKEND_URL = "https://surveyai-backend-production.up.railway.app"


def load_keys() -> dict[str, str]:
    path = Path(os.environ.get("SURVEYAI_KEYS_FILE", Path.home() / "Desktop" / "elevenlabs-keys.env"))
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            name, value = line.split("=", 1)
            values[name.strip()] = value.strip().strip('"').strip("'")
    # The LiveKit SDK and the ElevenLabs plugin read these environment variables.
    os.environ.setdefault("LIVEKIT_URL", values["LIVEKIT_URL"])
    os.environ.setdefault("LIVEKIT_API_KEY", values["LIVEKIT_API_KEY"])
    os.environ.setdefault("LIVEKIT_API_SECRET", values["LIVEKIT_API_SECRET"])
    os.environ.setdefault("ELEVEN_API_KEY", values["NEW_ELEVENLABS_API_KEY"])
    return values


def read_state() -> dict:
    return json.loads(STATE_FILE.read_text(encoding="utf-8")) if STATE_FILE.exists() else {}


def write_state(state: dict) -> None:
    STATE_FILE.write_text(json.dumps(state, indent=2), encoding="utf-8")
