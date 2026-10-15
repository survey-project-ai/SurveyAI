package com.yourcompany.surveyai.response.infrastructure.provider.livekit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.surveyai.call.application.provider.ProviderWebhookEvent;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import com.yourcompany.surveyai.response.application.model.IngestedSurveyResult;
import com.yourcompany.surveyai.response.application.provider.ProviderSurveyResultMapper;
import com.yourcompany.surveyai.response.domain.enums.SurveyResponseStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

// The LiveKit agent records every answer through the interview tools during the call, and the
// interview decides whether the response is complete. The agent's final event adds the transcript.
@Component
public class LiveKitSurveyResultMapper implements ProviderSurveyResultMapper {

    private final ObjectMapper objectMapper;

    public LiveKitSurveyResultMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public CallProvider getProvider() {
        return CallProvider.LIVEKIT;
    }

    @Override
    public IngestedSurveyResult map(CallAttempt callAttempt, ProviderWebhookEvent event) {
        JsonNode root = readTree(event.rawPayload());
        boolean finished = event.jobStatus() != null && event.jobStatus() != CallJobStatus.QUEUED
                && event.jobStatus() != CallJobStatus.IN_PROGRESS;
        boolean neverTalked = event.transcriptText() == null || event.transcriptText().isBlank();
        boolean failed = event.jobStatus() == CallJobStatus.FAILED || event.jobStatus() == CallJobStatus.CANCELLED;

        Map<String, Object> transcriptJson = new LinkedHashMap<>();
        transcriptJson.put("transcript", root.has("transcript") ? root.get("transcript") : List.of());
        transcriptJson.put("providerCallId", event.providerCallId());

        return new IngestedSurveyResult(
                // Only reached when the interview did not decide the outcome itself.
                failed && neverTalked ? SurveyResponseStatus.ABANDONED : SurveyResponseStatus.PARTIAL,
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                callAttempt.getConnectedAt() != null ? callAttempt.getConnectedAt() : callAttempt.getDialedAt(),
                finished ? firstNonNull(event.occurredAt(), OffsetDateTime.now()) : null,
                event.transcriptText(),
                toJson(transcriptJson),
                root.hasNonNull("summary") && !root.get("summary").asText().isBlank() ? root.get("summary").asText() : null,
                toJson(Map.of("event", event.eventType() == null ? "" : event.eventType(), "providerCallId",
                        event.providerCallId() == null ? "" : event.providerCallId())),
                List.of(),
                List.of()
        );
    }

    private JsonNode readTree(String rawJson) {
        try {
            return objectMapper.readTree(rawJson == null ? "{}" : rawJson);
        } catch (JsonProcessingException error) {
            return objectMapper.createObjectNode();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            return "{}";
        }
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }
}
