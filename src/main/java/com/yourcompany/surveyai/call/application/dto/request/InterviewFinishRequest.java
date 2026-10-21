package com.yourcompany.surveyai.call.application.dto.request;

import com.yourcompany.surveyai.response.domain.enums.SurveyResponseStatus;
import java.util.Map;
import java.util.UUID;

// inferredAnswers is optional: answers the agent inferred instead of asking, by kind, such as
// {"gender": {"value": "kadın", "confidence": 0.97}} from the callee's voice.
public record InterviewFinishRequest(
        UUID callAttemptId,
        String providerCallId,
        String idempotencyKey,
        SurveyResponseStatus requestedStatus,
        Boolean suppressClosingMessage,
        Map<String, InterviewInferredAnswer> inferredAnswers
) {
    public InterviewFinishRequest(
            UUID callAttemptId,
            String providerCallId,
            String idempotencyKey,
            SurveyResponseStatus requestedStatus,
            Boolean suppressClosingMessage
    ) {
        this(callAttemptId, providerCallId, idempotencyKey, requestedStatus, suppressClosingMessage, null);
    }
}
