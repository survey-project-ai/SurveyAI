package com.yourcompany.surveyai.operation.application.dto.response;

import java.util.List;

// Company-wide numbers for the dashboard, with the same definitions as the operation analytics.
public record OperationDashboardTotalsDto(
        List<OperationContactStatusCountDto> contactStatusCounts,
        long totalContacts,
        long respondedContacts,
        List<OperationAnalyticsTrendPointDto> responseTrend,
        long totalCallJobs,
        long queuedJobs,
        long inProgressJobs,
        long completedCallJobs,
        long failedCallJobs,
        long totalCallAttempts
) {
}
