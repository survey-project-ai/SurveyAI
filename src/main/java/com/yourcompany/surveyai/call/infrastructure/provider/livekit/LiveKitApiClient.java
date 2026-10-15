package com.yourcompany.surveyai.call.infrastructure.provider.livekit;

import com.yourcompany.surveyai.call.configuration.VoiceProviderConfiguration;

// The LiveKit server API calls the backend needs. Each returns the response body as JSON.
public interface LiveKitApiClient {

    // Sends the voice agent to the room, creating the room; the agent then dials the callee.
    String createDispatch(String roomName, String metadataJson, VoiceProviderConfiguration configuration);

    String listRooms(String roomName, VoiceProviderConfiguration configuration);

    // Ends the call: the agent and the callee's phone line are removed with the room.
    String deleteRoom(String roomName, VoiceProviderConfiguration configuration);
}
