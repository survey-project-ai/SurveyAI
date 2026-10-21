package com.yourcompany.surveyai.call.application.dto.request;

// An answer the agent inferred instead of asking, such as the callee's gender from their voice.
public record InterviewInferredAnswer(
        String value,
        Double confidence
) {
}
