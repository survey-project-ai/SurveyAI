package com.yourcompany.surveyai.response.repository;

import com.yourcompany.surveyai.response.domain.enums.SurveyResponseStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

// What operation analytics reads from a survey response. Loading the entity instead would also load
// the transcript text and the transcript JSON (which holds the provider's whole transcript) of every
// response on every analytics poll.
public record SurveyResponseAnalyticsRow(
        UUID id,
        UUID operationContactId,
        String contactFirstName,
        String contactLastName,
        UUID callJobId,
        SurveyResponseStatus status,
        BigDecimal completionPercent,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt,
        String consentState
) {
}
