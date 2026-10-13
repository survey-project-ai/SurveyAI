"""Creates (once) the LiveKit outbound SIP trunk for the Netgsm line and stores its id in .state.json."""
import asyncio

from livekit import api

from common import CALLER_NUMBER, SIP_ADDRESS, load_keys, read_state, write_state

TRUNK_NAME = "netgsm-outbound"


async def main() -> None:
    keys = load_keys()
    state = read_state()
    async with api.LiveKitAPI() as lkapi:
        existing = await lkapi.sip.list_outbound_trunk(api.ListSIPOutboundTrunkRequest())
        trunk = next((t for t in existing.items if t.name == TRUNK_NAME), None)
        if trunk is None:
            trunk = await lkapi.sip.create_outbound_trunk(api.CreateSIPOutboundTrunkRequest(
                trunk=api.SIPOutboundTrunkInfo(
                    name=TRUNK_NAME,
                    address=SIP_ADDRESS,
                    transport=api.SIPTransport.SIP_TRANSPORT_TCP,
                    numbers=[CALLER_NUMBER],
                    auth_username=keys["NETGSM_SIP_USERNAME"],
                    auth_password=keys["NETGSM_SIP_PASSWORD"],
                    media_encryption=api.SIPMediaEncryption.SIP_MEDIA_ENCRYPT_DISABLE,
                )
            ))
            print("created outbound trunk", trunk.sip_trunk_id)
        else:
            print("outbound trunk already exists", trunk.sip_trunk_id)
    state["sip_trunk_id"] = trunk.sip_trunk_id
    write_state(state)


if __name__ == "__main__":
    asyncio.run(main())
