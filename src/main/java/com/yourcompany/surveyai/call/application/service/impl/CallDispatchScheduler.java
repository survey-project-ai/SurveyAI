package com.yourcompany.surveyai.call.application.service.impl;

import com.yourcompany.surveyai.call.application.service.CallJobDispatcher;
import com.yourcompany.surveyai.operation.domain.enums.OperationStatus;
import com.yourcompany.surveyai.operation.repository.OperationRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Keeps every running operation moving: places the next call when the previous one ended without a
// webhook, when a retry becomes due, or when calling hours open. Safe to run on several instances
// because claiming a call locks the operation row.
@Component
@ConditionalOnProperty(name = "surveyai.calling.queue.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class CallDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(CallDispatchScheduler.class);

    private final OperationRepository operationRepository;
    private final CallJobDispatcher callJobDispatcher;

    public CallDispatchScheduler(OperationRepository operationRepository, CallJobDispatcher callJobDispatcher) {
        this.operationRepository = operationRepository;
        this.callJobDispatcher = callJobDispatcher;
    }

    @Scheduled(
            fixedDelayString = "${surveyai.calling.queue.dispatch-interval-ms:15000}",
            initialDelayString = "${surveyai.calling.queue.dispatch-initial-delay-ms:20000}"
    )
    public void dispatchRunningOperations() {
        for (UUID operationId : operationRepository.findIdsByStatusAndDeletedAtIsNull(OperationStatus.RUNNING)) {
            try {
                callJobDispatcher.dispatchAvailableJobs(operationId);
            } catch (RuntimeException error) {
                log.warn("Scheduled dispatch failed. operationId={} message={}", operationId, error.getMessage(), error);
            }
        }
    }
}
