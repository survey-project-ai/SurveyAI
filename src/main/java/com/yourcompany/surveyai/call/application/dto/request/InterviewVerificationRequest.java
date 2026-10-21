package com.yourcompany.surveyai.call.application.dto.request;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// The post-call check of a call's answers: each answer cut from the recording and transcribed again by
// other speech recognition models, which the engine reads with the rules of the call.
public record InterviewVerificationRequest(
        UUID callAttemptId,
        String providerCallId,
        List<AnswerCheck> checks
) {
    // transcripts: model name -> what it heard in the answer's clip.
    public record AnswerCheck(
            UUID questionId,
            String liveText,
            Map<String, String> transcripts
    ) {
    }
}
