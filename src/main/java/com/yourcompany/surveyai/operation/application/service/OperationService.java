package com.yourcompany.surveyai.operation.application.service;

import com.yourcompany.surveyai.operation.application.dto.request.CreateOperationRequest;
import com.yourcompany.surveyai.operation.application.dto.response.OperationAnalyticsResponseDto;
import com.yourcompany.surveyai.operation.application.dto.response.OperationDashboardTotalsDto;
import com.yourcompany.surveyai.operation.application.dto.response.OperationResponseDto;
import java.util.List;
import java.util.UUID;

public interface OperationService {

    OperationResponseDto createOperation(UUID companyId, CreateOperationRequest request);

    OperationResponseDto getOperationById(UUID companyId, UUID operationId);

    List<OperationResponseDto> listOperationsByCompany(UUID companyId);

    OperationResponseDto startOperation(UUID companyId, UUID operationId);

    OperationResponseDto pauseOperation(UUID companyId, UUID operationId);

    OperationResponseDto resumeOperation(UUID companyId, UUID operationId);

    void deleteOperation(UUID companyId, UUID operationId);

    OperationAnalyticsResponseDto getOperationAnalytics(UUID companyId, UUID operationId);

    OperationDashboardTotalsDto getDashboardTotals(UUID companyId);
}
