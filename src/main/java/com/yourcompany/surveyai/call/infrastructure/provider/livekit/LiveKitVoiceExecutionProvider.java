package com.yourcompany.surveyai.call.infrastructure.provider.livekit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.surveyai.call.application.model.ProviderCorrelationMetadata;
import com.yourcompany.surveyai.call.application.provider.ProviderCallStatusRequest;
import com.yourcompany.surveyai.call.application.provider.ProviderCallStatusResult;
import com.yourcompany.surveyai.call.application.provider.ProviderCancelRequest;
import com.yourcompany.surveyai.call.application.provider.ProviderCancelResult;
import com.yourcompany.surveyai.call.application.provider.ProviderConfigurationValidationResult;
import com.yourcompany.surveyai.call.application.provider.ProviderDispatchException;
import com.yourcompany.surveyai.call.application.provider.ProviderDispatchRequest;
import com.yourcompany.surveyai.call.application.provider.ProviderDispatchResult;
import com.yourcompany.surveyai.call.application.provider.ProviderWebhookEvent;
import com.yourcompany.surveyai.call.application.provider.ProviderWebhookRequest;
import com.yourcompany.surveyai.call.application.provider.VoiceExecutionProvider;
import com.yourcompany.surveyai.call.application.support.SurveyAsrKeywords;
import com.yourcompany.surveyai.call.configuration.VoiceProviderConfiguration;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import com.yourcompany.surveyai.common.exception.ValidationException;
import com.yourcompany.surveyai.operation.application.support.OperationAutoEntityLexiconService;
import com.yourcompany.surveyai.operation.support.OperationContactPhoneResolver;
import com.yourcompany.surveyai.operation.support.PhoneNumberNormalizer;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

// Our own voice agent (voice-agent/) on LiveKit. The backend sends the agent to a room named after the
// call attempt; the agent dials the callee over the SIP trunk, runs the interview through the interview
// tool API, and posts the call's progress to /api/v1/provider-webhooks/LIVEKIT.
//
// Those events are signed like ElevenLabs' webhooks, with the provider's webhook secret:
//   X-SurveyAI-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "<t>.<body>">
// and look like:
//   {"event": "ringing" | "answered" | "ended" | "failed" | "no_answer" | "busy" | "voicemail" | "cancelled",
//    "room": "call-<callAttemptId>", "callAttemptId": "...", "callJobId": "...", "idempotencyKey": "...",
//    "timestamp": <unix seconds>, "durationSeconds": 95, "sipStatusCode": 486, "sipStatus": "Busy Here",
//    "reason": "...", "transcript": [{"role": "agent" | "user", "text": "..."}], "summary": "..."}
// "failed" means the callee's phone never answered; the SIP status says why.
@Component
public class LiveKitVoiceExecutionProvider implements VoiceExecutionProvider {

    static final String SIGNATURE_HEADER = "X-SurveyAI-Signature";
    static final String ROOM_PREFIX = "call-";

    private static final Logger log = LoggerFactory.getLogger(LiveKitVoiceExecutionProvider.class);
    // The room is created with the dispatch, but a status check right after it must not fail the call.
    private static final Duration MISSING_ROOM_GRACE = Duration.ofSeconds(60);

    private final ObjectMapper objectMapper;
    private final LiveKitApiClient apiClient;
    private final OperationAutoEntityLexiconService operationAutoEntityLexiconService;

    public LiveKitVoiceExecutionProvider(
            ObjectMapper objectMapper,
            LiveKitApiClient apiClient,
            OperationAutoEntityLexiconService operationAutoEntityLexiconService
    ) {
        this.objectMapper = objectMapper;
        this.apiClient = apiClient;
        this.operationAutoEntityLexiconService = operationAutoEntityLexiconService;
    }

    @Override
    public CallProvider getProvider() {
        return CallProvider.LIVEKIT;
    }

    @Override
    public ProviderDispatchResult dispatchCallJob(ProviderDispatchRequest request, VoiceProviderConfiguration configuration) {
        OffsetDateTime now = OffsetDateTime.now();
        if (configuration.mockMode()) {
            String mockRoomName = "mock-" + request.callAttempt().getId();
            return new ProviderDispatchResult(
                    CallProvider.LIVEKIT,
                    mockRoomName,
                    CallJobStatus.QUEUED,
                    CallAttemptStatus.INITIATED,
                    now,
                    null,
                    null,
                    toJson(Map.of("mode", "MOCK", "room", mockRoomName, "callAttemptId", request.callAttempt().getId().toString()))
            );
        }

        String roomName = roomName(request.callAttempt());
        String metadata = toJson(buildDispatchMetadata(request, configuration));
        try {
            String responseBody = apiClient.createDispatch(roomName, metadata, configuration);
            log.info(
                    "LiveKit voice agent dispatched. callJobId={} callAttemptId={} room={} agentName={}",
                    request.callJob().getId(),
                    request.callAttempt().getId(),
                    roomName,
                    configuration.agentId()
            );
            return new ProviderDispatchResult(
                    CallProvider.LIVEKIT,
                    roomName,
                    CallJobStatus.QUEUED,
                    CallAttemptStatus.INITIATED,
                    now,
                    null,
                    null,
                    responseBody
            );
        } catch (RestClientResponseException error) {
            log.warn(
                    "LiveKit dispatch rejected. callJobId={} status={} responseBody={}",
                    request.callJob().getId(),
                    error.getStatusCode(),
                    error.getResponseBodyAsString()
            );
            throw new ProviderDispatchException(
                    "LiveKit dispatch failed: " + error.getResponseBodyAsString(),
                    isRetryableStatus(error.getStatusCode().value()),
                    error
            );
        } catch (ResourceAccessException error) {
            log.warn("LiveKit dispatch failed. callJobId={} message={}", request.callJob().getId(), error.getMessage());
            throw new ProviderDispatchException("LiveKit dispatch failed: " + error.getMessage(), isConnectionFailure(error), error);
        } catch (RuntimeException error) {
            log.warn("LiveKit dispatch failed. callJobId={} message={}", request.callJob().getId(), error.getMessage());
            throw new ProviderDispatchException("LiveKit dispatch failed: " + error.getMessage(), false, error);
        }
    }

    // The agent reports every status change itself. Polling only catches a call whose room closed
    // without a final event, for example because the agent crashed.
    @Override
    public ProviderCallStatusResult fetchCallStatus(ProviderCallStatusRequest request, VoiceProviderConfiguration configuration) {
        CallAttempt callAttempt = request.callAttempt();
        String roomName = callAttempt.getProviderCallId();
        OffsetDateTime now = OffsetDateTime.now();
        if (!hasText(roomName) || configuration.mockMode() || roomName.startsWith("mock-")) {
            return new ProviderCallStatusResult(null, null, now, null);
        }

        String responseBody = apiClient.listRooms(roomName, configuration);
        if (roomExists(readJson(responseBody), roomName) || dialedWithin(callAttempt, MISSING_ROOM_GRACE, now)) {
            return new ProviderCallStatusResult(null, null, now, responseBody);
        }
        log.warn(
                "LiveKit room closed without a final call status. callAttemptId={} room={} attemptStatus={}",
                callAttempt.getId(),
                roomName,
                callAttempt.getStatus()
        );
        return new ProviderCallStatusResult(
                CallJobStatus.FAILED,
                CallAttemptStatus.FAILED,
                now,
                toJson(Map.of("room", roomName, "roomFound", false, "reason", "The room closed without a final status from the voice agent"))
        );
    }

    @Override
    public ProviderCancelResult cancelCall(ProviderCancelRequest request, VoiceProviderConfiguration configuration) {
        if (configuration.mockMode()) {
            return new ProviderCancelResult(true, CallJobStatus.CANCELLED, "Mock call marked as cancelled");
        }
        String roomName = request.callAttempt().getProviderCallId();
        if (!hasText(roomName)) {
            return new ProviderCancelResult(false, request.callAttempt().getCallJob().getStatus(), "The call has no LiveKit room yet");
        }
        apiClient.deleteRoom(roomName, configuration);
        return new ProviderCancelResult(true, CallJobStatus.CANCELLED, "LiveKit room deleted; the call has ended");
    }

    @Override
    public ProviderConfigurationValidationResult validateConfiguration(VoiceProviderConfiguration configuration) {
        if (!configuration.enabled()) {
            return ProviderConfigurationValidationResult.failure("LiveKit provider is disabled");
        }
        if (configuration.mode() == null) {
            return ProviderConfigurationValidationResult.failure("LiveKit mode is required");
        }
        if (configuration.mockMode()) {
            return ProviderConfigurationValidationResult.success();
        }
        if (!hasText(configuration.baseUrl())) {
            return ProviderConfigurationValidationResult.failure("LiveKit URL is required");
        }
        if (!hasText(configuration.apiKey()) || !hasText(configuration.settings().get(HttpLiveKitApiClient.API_SECRET_SETTING))) {
            return ProviderConfigurationValidationResult.failure("LiveKit API key and secret are required");
        }
        if (!hasText(configuration.agentId())) {
            return ProviderConfigurationValidationResult.failure("LiveKit agent name is required");
        }
        if (!hasText(configuration.phoneNumberId())) {
            return ProviderConfigurationValidationResult.failure("LiveKit SIP trunk id is required");
        }
        if (!hasText(configuration.webhookSecret())) {
            return ProviderConfigurationValidationResult.failure("LiveKit webhook secret is required");
        }
        if (!hasText(configuration.settings().get("tool-api-secret"))) {
            return ProviderConfigurationValidationResult.failure("LiveKit tool API secret is required");
        }
        return ProviderConfigurationValidationResult.success();
    }

    // Unlike the ElevenLabs adapter, an unsigned event is never accepted outside mock mode: these
    // events change call outcomes and come from our own agent, which always has the secret.
    @Override
    public boolean verifyWebhook(ProviderWebhookRequest request, VoiceProviderConfiguration configuration) {
        if (configuration.mockMode()) {
            return true;
        }
        String secret = configuration.webhookSecret();
        String signatureHeader = firstHeader(request.headers(), SIGNATURE_HEADER);
        if (!hasText(secret) || !hasText(signatureHeader)) {
            return false;
        }

        Map<String, String> parts = parseSignature(signatureHeader);
        String timestampValue = parts.get("t");
        String providedSignature = parts.get("v1");
        if (timestampValue == null || providedSignature == null) {
            return false;
        }
        try {
            long timestamp = Long.parseLong(timestampValue);
            if (Math.abs(Instant.now().getEpochSecond() - timestamp) > configuration.webhookTimestampToleranceSeconds()) {
                return false;
            }
            String expected = HexFormat.of().formatHex(sign(timestampValue + "." + request.rawPayload(), secret));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    providedSignature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception error) {
            return false;
        }
    }

    @Override
    public List<ProviderWebhookEvent> parseWebhook(ProviderWebhookRequest request, VoiceProviderConfiguration configuration) {
        JsonNode root;
        try {
            root = objectMapper.readTree(request.rawPayload() == null ? "{}" : request.rawPayload());
        } catch (JsonProcessingException error) {
            log.warn("Failed to parse LiveKit agent event. message={}", error.getMessage());
            return List.of();
        }

        String eventType = text(root, "event");
        Integer sipStatusCode = integerValue(text(root, "sipStatusCode"));
        CallAttemptStatus attemptStatus = resolveAttemptStatus(eventType, sipStatusCode);
        if (attemptStatus == null) {
            log.warn("LiveKit agent event ignored because its type is unknown. event={} room={}", eventType, text(root, "room"));
            return List.of();
        }

        String roomName = text(root, "room");
        String transcriptText = extractTranscript(root.get("transcript"));
        boolean reachedCallee = attemptStatus == CallAttemptStatus.IN_PROGRESS || attemptStatus == CallAttemptStatus.COMPLETED;
        return List.of(new ProviderWebhookEvent(
                CallProvider.LIVEKIT,
                roomName,
                text(root, "idempotencyKey"),
                eventType,
                jobStatusFor(attemptStatus),
                attemptStatus,
                resolveOccurredAt(root.get("timestamp")),
                integerValue(text(root, "durationSeconds")),
                new ProviderCorrelationMetadata(
                        uuidValue(text(root, "operationId")),
                        uuidValue(text(root, "contactId")),
                        uuidValue(text(root, "callJobId")),
                        uuidValue(text(root, "callAttemptId"))
                ),
                sipStatusCode == null ? null : String.valueOf(sipStatusCode),
                // A call that ended after the callee answered did not fail, whatever ended it.
                reachedCallee ? null : firstNonBlank(text(root, "reason"), text(root, "sipStatus")),
                hasText(transcriptText) && hasText(roomName) ? "inline://livekit/rooms/" + roomName : null,
                transcriptText,
                request.rawPayload()
        ));
    }

    static String roomName(CallAttempt callAttempt) {
        return ROOM_PREFIX + callAttempt.getId();
    }

    // Why the callee's phone did not answer, from the SIP response to the agent's dial.
    static CallAttemptStatus attemptStatusForSipFailure(Integer sipStatusCode) {
        if (sipStatusCode == null) {
            return CallAttemptStatus.FAILED;
        }
        return switch (sipStatusCode) {
            // Busy, or the callee rejected the call: try again later, as a human interviewer would.
            case 486, 600, 603 -> CallAttemptStatus.BUSY;
            // Rang out, phone off or out of reach, or the agent's ringing timeout cancelled the dial.
            case 408, 480, 487 -> CallAttemptStatus.NO_ANSWER;
            default -> CallAttemptStatus.FAILED;
        };
    }

    private CallAttemptStatus resolveAttemptStatus(String eventType, Integer sipStatusCode) {
        if (eventType == null) {
            return null;
        }
        return switch (eventType.trim().toLowerCase(Locale.ROOT)) {
            case "ringing" -> CallAttemptStatus.RINGING;
            case "answered" -> CallAttemptStatus.IN_PROGRESS;
            case "ended" -> CallAttemptStatus.COMPLETED;
            case "failed" -> attemptStatusForSipFailure(sipStatusCode);
            case "no_answer" -> CallAttemptStatus.NO_ANSWER;
            case "busy" -> CallAttemptStatus.BUSY;
            case "voicemail" -> CallAttemptStatus.VOICEMAIL;
            case "cancelled" -> CallAttemptStatus.CANCELLED;
            default -> null;
        };
    }

    private CallJobStatus jobStatusFor(CallAttemptStatus attemptStatus) {
        return switch (attemptStatus) {
            case INITIATED, RINGING -> CallJobStatus.QUEUED;
            case IN_PROGRESS -> CallJobStatus.IN_PROGRESS;
            case COMPLETED -> CallJobStatus.COMPLETED;
            case CANCELLED -> CallJobStatus.CANCELLED;
            // The retry policy calls unanswered, busy and voicemail calls again later.
            case FAILED, NO_ANSWER, BUSY, VOICEMAIL -> CallJobStatus.FAILED;
        };
    }

    private Map<String, Object> buildDispatchMetadata(ProviderDispatchRequest request, VoiceProviderConfiguration configuration) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("callAttemptId", request.callAttempt().getId().toString());
        metadata.put("callJobId", request.callJob().getId().toString());
        metadata.put("operationId", request.operation().getId().toString());
        metadata.put("contactId", request.contact().getId().toString());
        metadata.put("surveyId", request.survey().getId().toString());
        metadata.put("idempotencyKey", request.callJob().getIdempotencyKey());
        metadata.put("phoneNumber", normalizePhoneNumber(OperationContactPhoneResolver.resolveDisplayPhoneNumber(request.contact())));
        metadata.put("sipTrunkId", configuration.phoneNumberId());
        metadata.put("languageCode", request.survey().getLanguageCode());
        metadata.put("asrKeywords", SurveyAsrKeywords.collect(
                request.survey(), request.operation(), objectMapper, operationAutoEntityLexiconService));
        return metadata;
    }

    private boolean roomExists(JsonNode listRoomsResponse, String roomName) {
        JsonNode rooms = listRoomsResponse.path("rooms");
        if (!rooms.isArray()) {
            return false;
        }
        for (JsonNode room : rooms) {
            if (roomName.equals(room.path("name").asText(null))) {
                return true;
            }
        }
        return false;
    }

    private boolean dialedWithin(CallAttempt callAttempt, Duration window, OffsetDateTime now) {
        return callAttempt.getDialedAt() == null || callAttempt.getDialedAt().isAfter(now.minus(window));
    }

    private String extractTranscript(JsonNode transcript) {
        if (transcript == null || transcript.isNull()) {
            return null;
        }
        if (transcript.isTextual()) {
            return firstNonBlank(transcript.asText().trim());
        }
        if (!transcript.isArray()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode turn : transcript) {
            String message = text(turn, "text");
            if (!hasText(message)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            String role = text(turn, "role");
            if (hasText(role)) {
                builder.append(role.trim()).append(": ");
            }
            builder.append(message.trim());
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private OffsetDateTime resolveOccurredAt(JsonNode timestamp) {
        if (timestamp == null || timestamp.isNull()) {
            return OffsetDateTime.now();
        }
        try {
            if (timestamp.isNumber()) {
                long millis = Math.round(timestamp.asDouble() * 1000);
                return OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
            }
            return OffsetDateTime.parse(timestamp.asText());
        } catch (RuntimeException ignored) {
            return OffsetDateTime.now();
        }
    }

    // Only responses that prove LiveKit did not create the dispatch are retryable.
    private boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || statusCode == 502 || statusCode == 503;
    }

    // A refused or unresolvable connection means the request never reached LiveKit. A read timeout
    // does not: the agent may already be dialing.
    private boolean isConnectionFailure(ResourceAccessException error) {
        Throwable cause = error.getCause();
        if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
            return true;
        }
        return cause instanceof SocketTimeoutException
                && cause.getMessage() != null
                && cause.getMessage().toLowerCase(Locale.ROOT).contains("connect");
    }

    private String normalizePhoneNumber(String value) {
        if (!hasText(value)) {
            throw new ValidationException("Contact phone number is required for LiveKit dispatch");
        }
        return "+" + PhoneNumberNormalizer.normalize(value);
    }

    private byte[] sign(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, String> parseSignature(String headerValue) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : headerValue.split(",")) {
            String[] keyValue = part.trim().split("=", 2);
            if (keyValue.length == 2) {
                result.put(keyValue[0].trim().toLowerCase(Locale.ROOT), keyValue[1].trim());
            }
        }
        return result;
    }

    private String firstHeader(Map<String, List<String>> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return entry.getValue().getFirst();
            }
        }
        return null;
    }

    private JsonNode readJson(String payload) {
        try {
            return objectMapper.readTree(payload == null ? "{}" : payload);
        } catch (JsonProcessingException error) {
            throw new ValidationException("Failed to parse LiveKit response");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new ValidationException("Failed to serialize LiveKit payload");
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value.isBlank() ? null : value;
    }

    private Integer integerValue(String value) {
        if (value == null) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(value.trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private UUID uuidValue(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
