package com.yourcompany.surveyai.call.infrastructure.provider.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.surveyai.call.application.model.ProviderCorrelationMetadata;
import com.yourcompany.surveyai.call.application.provider.ProviderCallStatusRequest;
import com.yourcompany.surveyai.call.application.provider.ProviderCallStatusResult;
import com.yourcompany.surveyai.call.application.provider.ProviderCancelRequest;
import com.yourcompany.surveyai.call.application.provider.ProviderCancelResult;
import com.yourcompany.surveyai.call.application.provider.ProviderConfigurationValidationResult;
import com.yourcompany.surveyai.call.application.provider.ProviderDispatchRequest;
import com.yourcompany.surveyai.call.application.provider.ProviderDispatchResult;
import com.yourcompany.surveyai.call.application.provider.ProviderWebhookEvent;
import com.yourcompany.surveyai.call.application.provider.ProviderWebhookRequest;
import com.yourcompany.surveyai.call.application.provider.VoiceExecutionProvider;
import com.yourcompany.surveyai.call.configuration.VoiceProviderConfiguration;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MockVoiceExecutionProvider implements VoiceExecutionProvider {

    private final ObjectMapper objectMapper;

    public MockVoiceExecutionProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public CallProvider getProvider() {
        return CallProvider.MOCK;
    }

    @Override
    public ProviderDispatchResult dispatchCallJob(ProviderDispatchRequest request, VoiceProviderConfiguration configuration) {
        // One id per attempt: retries of a job must not collide on UNIQUE (provider, provider_call_id).
        String providerCallId = "mock-" + request.callAttempt().getId();
        OffsetDateTime now = OffsetDateTime.now();
        String rawPayload = "{\"provider\":\"MOCK\",\"providerCallId\":\"" + providerCallId + "\",\"status\":\"QUEUED\"}";
        return new ProviderDispatchResult(
                CallProvider.MOCK,
                providerCallId,
                CallJobStatus.QUEUED,
                CallAttemptStatus.INITIATED,
                now,
                null,
                null,
                rawPayload
        );
    }

    @Override
    public ProviderCallStatusResult fetchCallStatus(ProviderCallStatusRequest request, VoiceProviderConfiguration configuration) {
        return new ProviderCallStatusResult(
                request.callAttempt().getCallJob().getStatus(),
                request.callAttempt().getStatus(),
                OffsetDateTime.now(),
                request.callAttempt().getRawProviderPayload()
        );
    }

    @Override
    public ProviderCancelResult cancelCall(ProviderCancelRequest request, VoiceProviderConfiguration configuration) {
        return new ProviderCancelResult(true, CallJobStatus.CANCELLED, "Mock cancellation accepted");
    }

    @Override
    public ProviderConfigurationValidationResult validateConfiguration(VoiceProviderConfiguration configuration) {
        return configuration.enabled()
                ? ProviderConfigurationValidationResult.success()
                : ProviderConfigurationValidationResult.failure("Mock provider is disabled");
    }

    @Override
    public boolean verifyWebhook(ProviderWebhookRequest request, VoiceProviderConfiguration configuration) {
        return true;
    }

    @Override
    public List<ProviderWebhookEvent> parseWebhook(ProviderWebhookRequest request, VoiceProviderConfiguration configuration) {
        try {
            JsonNode root = objectMapper.readTree(request.rawPayload());
            OffsetDateTime occurredAt = root.hasNonNull("occurredAt")
                    ? OffsetDateTime.parse(root.get("occurredAt").asText())
                    : OffsetDateTime.now();
            CallJobStatus jobStatus = CallJobStatus.valueOf(root.path("status").asText("COMPLETED"));
            CallAttemptStatus attemptStatus = mapAttemptStatus(jobStatus);
            return List.of(new ProviderWebhookEvent(
                    CallProvider.MOCK,
                    root.path("providerCallId").asText(null),
                    root.path("idempotencyKey").asText(null),
                    root.path("type").asText("mock_webhook"),
                    jobStatus,
                    attemptStatus,
                    occurredAt,
                    root.hasNonNull("durationSeconds") ? root.get("durationSeconds").asInt() : null,
                    new ProviderCorrelationMetadata(
                            null,
                            null,
                            null,
                            null
                    ),
                    root.path("errorCode").asText(null),
                    root.path("errorMessage").asText(null),
                    null,
                    root.path("transcript").asText(null),
                    request.rawPayload()
            ));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private CallAttemptStatus mapAttemptStatus(CallJobStatus status) {
        return switch (status) {
            case PENDING, QUEUED -> CallAttemptStatus.INITIATED;
            case IN_PROGRESS -> CallAttemptStatus.IN_PROGRESS;
            case COMPLETED -> CallAttemptStatus.COMPLETED;
            case RETRY, FAILED, DEAD_LETTER -> CallAttemptStatus.FAILED;
            case CANCELLED -> CallAttemptStatus.CANCELLED;
        };
    }
}
