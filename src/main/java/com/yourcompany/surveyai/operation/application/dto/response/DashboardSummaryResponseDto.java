package com.yourcompany.surveyai.operation.application.dto.response;

import com.yourcompany.surveyai.call.application.dto.response.CallJobResponseDto;
import java.util.List;

public record DashboardSummaryResponseDto(
        OperationDashboardTotalsDto totals,
        List<CallJobResponseDto> recentCallJobs
) {
}
