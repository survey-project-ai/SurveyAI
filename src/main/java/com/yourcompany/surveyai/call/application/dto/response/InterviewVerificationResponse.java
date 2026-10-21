package com.yourcompany.surveyai.call.application.dto.response;

import java.util.List;
import java.util.UUID;

// status: verified, corrected, review, no_answer or not_checked (open-ended answers). reason says why an
// answer goes to a person: checks_disagree, missed_in_call, affects_branching or random_sample.
public record InterviewVerificationResponse(
        UUID callAttemptId,
        List<Result> results
) {
    public record Result(
            UUID questionId,
            String questionCode,
            String status,
            String reason,
            String recorded,
            String suggested
    ) {
    }
}
