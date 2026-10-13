package com.yourcompany.surveyai.operation.api;

import com.yourcompany.surveyai.call.application.service.CallJobService;
import com.yourcompany.surveyai.operation.application.dto.response.DashboardSummaryResponseDto;
import com.yourcompany.surveyai.operation.application.service.OperationService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Everything the home page shows in one request, instead of three requests per operation.
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private static final int RECENT_CALL_JOB_LIMIT = 6;

    private final OperationService operationService;
    private final CallJobService callJobService;

    public DashboardController(OperationService operationService, CallJobService callJobService) {
        this.operationService = operationService;
        this.callJobService = callJobService;
    }

    @GetMapping("/summary")
    public ResponseEntity<DashboardSummaryResponseDto> getSummary(@RequestParam UUID companyId) {
        return ResponseEntity.ok(new DashboardSummaryResponseDto(
                operationService.getDashboardTotals(companyId),
                callJobService.listRecentlyCalledJobs(companyId, RECENT_CALL_JOB_LIMIT)
        ));
    }
}
