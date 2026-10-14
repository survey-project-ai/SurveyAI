"""Dispatches the prototype agent to a new room; the agent then calls the given number.

python call.py <number>                 -> agent.py (scripted pipeline)
python call.py <number> <model>         -> agent_realtime.py with that speech-to-speech model,
                                           e.g. openai/gpt-realtime-2.1-mini or openai/gpt-realtime-2.1
"""
import asyncio
import json
import sys
import time

from livekit import api

from common import AGENT_NAME, load_keys


async def main(phone: str, model: str | None) -> None:
    load_keys()
    room = f"survey-test-{int(time.time())}"
    metadata = {"phone": phone} if model is None else {"phone": phone, "model": model}
    async with api.LiveKitAPI() as lkapi:
        dispatch = await lkapi.agent_dispatch.create_dispatch(api.CreateAgentDispatchRequest(
            agent_name=AGENT_NAME if model is None else "survey-realtime",
            room=room,
            metadata=json.dumps(metadata),
        ))
    print("dispatched", dispatch.id, "room", room)


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None))
