package com.yourcompany.surveyai.call.application.service;

import com.yourcompany.surveyai.call.application.dto.request.UpdateCallJobSurveyResponseRequest;
import com.yourcompany.surveyai.call.application.dto.response.CallJobListStatusDto;
import com.yourcompany.surveyai.call.application.dto.response.CallJobDetailResponseDto;
import com.yourcompany.surveyai.call.application.dto.response.CallJobPageResponseDto;
import com.yourcompany.surveyai.call.application.dto.response.CallJobResponseDto;
import java.util.List;
import java.util.UUID;

public interface CallJobService {

    // The company's jobs that were dialed at least once, latest change first.
    List<CallJobResponseDto> listRecentlyCalledJobs(UUID companyId, int limit);

    CallJobPageResponseDto listOperationCallJobs(
            UUID companyId,
            UUID operationId,
            int page,
            int size,
            String query,
            List<CallJobListStatusDto> statuses,
            String sortBy,
            String direction
    );

    CallJobDetailResponseDto getOperationCallJobDetail(
            UUID companyId,
            UUID operationId,
            UUID callJobId
    );

    CallJobDetailResponseDto retryOperationCallJob(
            UUID companyId,
            UUID operationId,
            UUID callJobId
    );

    CallJobDetailResponseDto redialOperationCallJob(
            UUID companyId,
            UUID operationId,
            UUID callJobId
    );

    CallJobDetailResponseDto updateOperationCallJobSurveyResponse(
            UUID companyId,
            UUID operationId,
            UUID callJobId,
            UpdateCallJobSurveyResponseRequest request
    );
}
