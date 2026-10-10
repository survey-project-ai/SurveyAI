package com.yourcompany.surveyai.call.application.dto.request;

import java.util.UUID;

// questionId is optional: when the agent echoes the id of the question it is answering, a submission
// for a question that is no longer current is ignored instead of being applied to the next question.
public record InterviewAnswerRequest(
        UUID callAttemptId,
        String providerCallId,
        String idempotencyKey,
        String utteranceText,
        InterviewConversationSignal signal,
        UUID questionId
) {

    public InterviewAnswerRequest(
            UUID callAttemptId,
            String providerCallId,
            String idempotencyKey,
            String utteranceText,
            InterviewConversationSignal signal
    ) {
        this(callAttemptId, providerCallId, idempotencyKey, utteranceText, signal, null);
    }
}
