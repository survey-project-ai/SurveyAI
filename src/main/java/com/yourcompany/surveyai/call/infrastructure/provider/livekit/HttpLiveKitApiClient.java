package com.yourcompany.surveyai.call.infrastructure.provider.livekit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.surveyai.call.configuration.VoiceProviderConfiguration;
import com.yourcompany.surveyai.common.exception.ValidationException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// Calls LiveKit's Twirp server API with JSON bodies, as LiveKit's JavaScript server SDK does.
@Component
public class HttpLiveKitApiClient implements LiveKitApiClient {

    static final String API_SECRET_SETTING = "api-secret";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration TOKEN_TTL = Duration.ofMinutes(10);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpLiveKitApiClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = restClientBuilder.requestFactory(factory).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String createDispatch(String roomName, String metadataJson, VoiceProviderConfiguration configuration) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agent_name", configuration.agentId());
        body.put("room", roomName);
        body.put("metadata", metadataJson);
        return post("AgentDispatchService", "CreateDispatch", body, Map.of("roomAdmin", true, "room", roomName), configuration);
    }

    @Override
    public String listRooms(String roomName, VoiceProviderConfiguration configuration) {
        return post("RoomService", "ListRooms", Map.of("names", List.of(roomName)), Map.of("roomList", true), configuration);
    }

    @Override
    public String deleteRoom(String roomName, VoiceProviderConfiguration configuration) {
        return post("RoomService", "DeleteRoom", Map.of("room", roomName), Map.of("roomCreate", true), configuration);
    }

    private String post(
            String service,
            String method,
            Map<String, Object> body,
            Map<String, Object> videoGrant,
            VoiceProviderConfiguration configuration
    ) {
        String token = LiveKitAccessToken.create(
                configuration.apiKey(),
                configuration.settings().get(API_SECRET_SETTING),
                videoGrant,
                TOKEN_TTL,
                Instant.now(),
                objectMapper
        );
        return restClient.post()
                .uri(apiBaseUrl(configuration.baseUrl()) + "/twirp/livekit." + service + "/" + method)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(toJson(body))
                .retrieve()
                .body(String.class);
    }

    // The project URL is usually given as the wss:// address clients connect to.
    static String apiBaseUrl(String baseUrl) {
        String trimmed = baseUrl.trim();
        if (trimmed.startsWith("wss://")) {
            trimmed = "https://" + trimmed.substring("wss://".length());
        } else if (trimmed.startsWith("ws://")) {
            trimmed = "http://" + trimmed.substring("ws://".length());
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException error) {
            throw new ValidationException("Failed to serialize the LiveKit request");
        }
    }
}
