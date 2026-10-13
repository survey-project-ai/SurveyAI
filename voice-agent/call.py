"""Dispatches the prototype agent to a new room; the agent then calls the given number."""
import asyncio
import json
import sys
import time

from livekit import api

from common import AGENT_NAME, load_keys


async def main(phone: str) -> None:
    load_keys()
    room = f"survey-test-{int(time.time())}"
    async with api.LiveKitAPI() as lkapi:
        dispatch = await lkapi.agent_dispatch.create_dispatch(api.CreateAgentDispatchRequest(
            agent_name=AGENT_NAME,
            room=room,
            metadata=json.dumps({"phone": phone}),
        ))
    print("dispatched", dispatch.id, "room", room)


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1]))
