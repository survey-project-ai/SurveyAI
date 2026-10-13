package com.yourcompany.surveyai.operation.application.dto.response;

import java.util.List;
import java.util.UUID;

public record OperationAnalyticsResponseDto(
        UUID operationId,
        long totalContacts,
        long totalCallJobs,
        long totalPreparedJobs,
        long totalCallsAttempted,
        long totalCallAttempts,
        long totalCompletedCalls,
        long queuedJobs,
        long inProgressJobs,
        long completedCallJobs,
        long failedCallJobs,
        long skippedCallJobs,
        long totalResponses,
        long respondedContacts,
        long completedResponses,
        long partialResponses,
        long abandonedResponses,
        long invalidResponses,
        double completionRate,
        double responseRate,
        double personResponseRate,
        double contactReachRate,
        double participationRate,
        double averageCompletionPercent,
        boolean partialData,
        String insightSummary,
        List<OperationAnalyticsInsightItemDto> insightItems,
        List<OperationAnalyticsBreakdownItemDto> outcomeBreakdown,
        List<OperationAnalyticsBreakdownItemDto> consentBreakdown,
        List<OperationAnalyticsAudienceBreakdownDto> audienceBreakdowns,
        List<OperationAnalyticsQuestionSummaryDto> questionSummaries,
        List<OperationAnalyticsQuestionGroupSummaryDto> questionGroups,
        List<OperationAnalyticsTrendPointDto> responseTrend
) {
}
