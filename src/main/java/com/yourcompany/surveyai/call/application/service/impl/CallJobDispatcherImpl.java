package com.yourcompany.surveyai.call.application.service.impl;

import com.yourcompany.surveyai.call.application.provider.CallProviderRegistry;
import com.yourcompany.surveyai.call.application.provider.ProviderCallStatusRequest;
import com.yourcompany.surveyai.call.application.provider.ProviderCallStatusResult;
import com.yourcompany.surveyai.call.application.provider.ProviderConfigurationValidationResult;
import com.yourcompany.surveyai.call.application.provider.ProviderDispatchException;
import com.yourcompany.surveyai.call.application.provider.ProviderDispatchRequest;
import com.yourcompany.surveyai.call.application.provider.ProviderDispatchResult;
import com.yourcompany.surveyai.call.application.provider.VoiceExecutionProvider;
import com.yourcompany.surveyai.call.application.service.CallJobDispatcher;
import com.yourcompany.surveyai.call.application.service.ProviderExecutionObservationService;
import com.yourcompany.surveyai.call.application.support.CallRetryPolicy;
import com.yourcompany.surveyai.call.application.support.CallStatusSnapshotApplier;
import com.yourcompany.surveyai.call.application.support.CallingHoursPolicy;
import com.yourcompany.surveyai.call.configuration.CallQueueProperties;
import com.yourcompany.surveyai.call.configuration.VoiceProviderConfiguration;
import com.yourcompany.surveyai.call.configuration.VoiceProviderConfigurationResolver;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import com.yourcompany.surveyai.call.repository.CallAttemptRepository;
import com.yourcompany.surveyai.call.repository.CallJobRepository;
import com.yourcompany.surveyai.common.exception.ValidationException;
import com.yourcompany.surveyai.operation.domain.entity.Operation;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.domain.enums.OperationContactStatus;
import com.yourcompany.surveyai.operation.domain.enums.OperationStatus;
import com.yourcompany.surveyai.operation.repository.OperationContactRepository;
import com.yourcompany.surveyai.operation.repository.OperationRepository;
import com.yourcompany.surveyai.survey.domain.entity.Survey;
import java.lang.management.ManagementFactory;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

// Places outbound calls in three steps so that no transaction is open while the provider is called:
// 1. claim: lock the operation row, check calling hours and the concurrency limit, pick the next job,
//    and commit a new attempt (the attempt exists before the call, so tool calls and webhooks find it);
// 2. call the provider outside any transaction;
// 3. record the result, or the failure and the retry decision, in a new transaction.
@Service
public class CallJobDispatcherImpl implements CallJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CallJobDispatcherImpl.class);
    private static final int CALL_ATTEMPT_FAILURE_REASON_MAX_LENGTH = 255;
    // Bounds one dispatch request, so a run of failing numbers cannot loop for long; the scheduler continues.
    private static final int MAX_CLAIMS_PER_DISPATCH = 10;

    private static final Set<CallJobStatus> DISPATCHABLE_JOB_STATUSES = EnumSet.of(
            CallJobStatus.PENDING,
            CallJobStatus.RETRY
    );

    private static final Set<CallJobStatus> ACTIVE_JOB_STATUSES = EnumSet.of(
            CallJobStatus.QUEUED,
            CallJobStatus.IN_PROGRESS
    );

    private final CallProviderRegistry callProviderRegistry;
    private final VoiceProviderConfigurationResolver configurationResolver;
    private final CallJobRepository callJobRepository;
    private final CallAttemptRepository callAttemptRepository;
    private final OperationContactRepository operationContactRepository;
    private final OperationRepository operationRepository;
    private final ProviderExecutionObservationService providerExecutionObservationService;
    private final CallRetryPolicy callRetryPolicy;
    private final CallingHoursPolicy callingHoursPolicy;
    private final CallStatusSnapshotApplier callStatusSnapshotApplier;
    private final CallQueueProperties queueProperties;
    private final TransactionTemplate transactionTemplate;
    private final Executor dispatchExecutor;
    private final String instanceId;

    public CallJobDispatcherImpl(
            CallProviderRegistry callProviderRegistry,
            VoiceProviderConfigurationResolver configurationResolver,
            CallJobRepository callJobRepository,
            CallAttemptRepository callAttemptRepository,
            OperationContactRepository operationContactRepository,
            OperationRepository operationRepository,
            ProviderExecutionObservationService providerExecutionObservationService,
            CallRetryPolicy callRetryPolicy,
            CallingHoursPolicy callingHoursPolicy,
            CallStatusSnapshotApplier callStatusSnapshotApplier,
            CallQueueProperties queueProperties,
            PlatformTransactionManager transactionManager,
            @Qualifier("callDispatchExecutor") Executor dispatchExecutor
    ) {
        this.callProviderRegistry = callProviderRegistry;
        this.configurationResolver = configurationResolver;
        this.callJobRepository = callJobRepository;
        this.callAttemptRepository = callAttemptRepository;
        this.operationContactRepository = operationContactRepository;
        this.operationRepository = operationRepository;
        this.providerExecutionObservationService = providerExecutionObservationService;
        this.callRetryPolicy = callRetryPolicy;
        this.callingHoursPolicy = callingHoursPolicy;
        this.callStatusSnapshotApplier = callStatusSnapshotApplier;
        this.queueProperties = queueProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.dispatchExecutor = dispatchExecutor;
        this.instanceId = trimToMaxLength(ManagementFactory.getRuntimeMXBean().getName(), 120);
    }

    @Override
    public void validateActiveProviderConfiguration() {
        resolveActiveProvider();
    }

    @Override
    public void requestDispatch(UUID operationId) {
        Runnable task = () -> {
            try {
                dispatchAvailableJobs(operationId);
            } catch (RuntimeException error) {
                log.warn("Requested dispatch failed. operationId={} message={}", operationId, error.getMessage(), error);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(operationId, task);
                }
            });
            return;
        }
        submit(operationId, task);
    }

    @Override
    public int dispatchAvailableJobs(UUID operationId) {
        int attempted = 0;
        for (int claimIndex = 0; claimIndex < MAX_CLAIMS_PER_DISPATCH; claimIndex++) {
            DispatchClaim claim = transactionTemplate.execute(status -> claimNextJob(operationId));
            if (claim == null) {
                break;
            }
            placeCall(claim);
            attempted++;
        }
        return attempted;
    }

    private void submit(UUID operationId, Runnable task) {
        try {
            dispatchExecutor.execute(task);
        } catch (RejectedExecutionException error) {
            log.warn("Dispatch request rejected; the scheduler will pick the operation up. operationId={}", operationId);
        }
    }

    private DispatchClaim claimNextJob(UUID operationId) {
        ActiveProvider activeProvider;
        try {
            activeProvider = resolveActiveProvider();
        } catch (ValidationException error) {
            log.warn("Skipping dispatch because the active provider cannot place calls. operationId={} reason={}", operationId, error.getMessage());
            return null;
        }

        // Empty when the operation is missing or another dispatcher holds its lock right now.
        Operation operation = operationRepository.findAndLockForDispatch(operationId).orElse(null);
        if (operation == null || operation.getDeletedAt() != null || operation.getStatus() != OperationStatus.RUNNING) {
            return null;
        }

        OffsetDateTime now = OffsetDateTime.now();
        expireStaleActiveJobs(operationId, now);
        if (!callingHoursPolicy.isWithinCallingHours(operation.getCompany(), now.toInstant())) {
            return null;
        }
        long activeCalls = callJobRepository.countByOperation_IdAndStatusInAndDeletedAtIsNull(operationId, ACTIVE_JOB_STATUSES);
        if (activeCalls >= Math.max(1, queueProperties.getMaxConcurrentCallsPerOperation())) {
            return null;
        }

        CallJob job = callJobRepository
                .findFirstByOperation_IdAndStatusInAndAvailableAtBeforeAndDeletedAtIsNullOrderByPriorityDescScheduledForAscCreatedAtAsc(
                        operationId,
                        DISPATCHABLE_JOB_STATUSES,
                        now.plusSeconds(1)
                )
                .orElse(null);
        if (job == null) {
            return null;
        }

        int attemptNumber = (job.getAttemptCount() == null ? 0 : job.getAttemptCount()) + 1;
        CallAttempt attempt = createPendingAttempt(job, activeProvider.provider().getProvider(), attemptNumber);
        job.setAttemptCount(attemptNumber);
        job.setStatus(CallJobStatus.QUEUED);
        job.setLockedAt(now);
        job.setLockedBy(instanceId);
        callJobRepository.save(job);

        OperationContact contact = job.getOperationContact();
        contact.setStatus(OperationContactStatus.CALLING);
        contact.setNextRetryAt(null);
        operationContactRepository.save(contact);

        // The provider runs after this transaction commits, so load everything it reads now.
        Survey survey = operation.getSurvey();
        Hibernate.initialize(operation.getCompany());
        Hibernate.initialize(survey);
        Hibernate.initialize(survey.getQuestions());
        Hibernate.initialize(contact);

        log.info(
                "Call job claimed. provider={} operationId={} callJobId={} callAttemptId={} attemptNumber={}",
                activeProvider.provider().getProvider(),
                operationId,
                job.getId(),
                attempt.getId(),
                attemptNumber
        );
        return new DispatchClaim(
                activeProvider.provider(),
                activeProvider.configuration(),
                new ProviderDispatchRequest(attempt, job, operation, contact, survey)
        );
    }

    // A call whose status never arrived would block the operation forever, because only a limited
    // number of calls may be active at once. It is failed without an automatic retry, since the call
    // may have reached the respondent.
    private void expireStaleActiveJobs(UUID operationId, OffsetDateTime now) {
        OffsetDateTime cutoff = now.minus(queueProperties.getStaleActiveCallAfter());
        for (CallJob staleJob : callJobRepository.findAllByOperation_IdAndStatusInAndUpdatedAtBeforeAndDeletedAtIsNull(
                operationId,
                ACTIVE_JOB_STATUSES,
                cutoff
        )) {
            callAttemptRepository.findTopByCallJob_IdAndDeletedAtIsNullOrderByAttemptNumberDesc(staleJob.getId())
                    .filter(attempt -> !CallStatusSnapshotApplier.isFinal(attempt.getStatus()))
                    .ifPresent(attempt -> {
                        attempt.setStatus(CallAttemptStatus.FAILED);
                        attempt.setEndedAt(now);
                        attempt.setFailureReason("Call status was not reported in time");
                        callAttemptRepository.save(attempt);
                    });
            staleJob.setLastErrorCode("STATUS_TIMEOUT");
            staleJob.setLastErrorMessage("Call status was not reported in time");
            callRetryPolicy.applyReportedStatus(staleJob, staleJob.getOperationContact(), CallJobStatus.FAILED, CallAttemptStatus.FAILED, now);
            callJobRepository.save(staleJob);
            operationContactRepository.save(staleJob.getOperationContact());
            log.warn("Expired a call job whose status never arrived. operationId={} callJobId={}", operationId, staleJob.getId());
        }
    }

    private void placeCall(DispatchClaim claim) {
        UUID callJobId = claim.request().callJob().getId();
        UUID callAttemptId = claim.request().callAttempt().getId();
        ProviderDispatchResult result;
        try {
            result = claim.provider().dispatchCallJob(claim.request(), claim.configuration());
        } catch (RuntimeException error) {
            boolean retryable = error instanceof ProviderDispatchException dispatchError && dispatchError.isRetryable();
            transactionTemplate.executeWithoutResult(status ->
                    recordDispatchFailure(callJobId, callAttemptId, claim.provider().getProvider(), error, retryable));
            return;
        }

        transactionTemplate.executeWithoutResult(status -> recordDispatchAccepted(callJobId, callAttemptId, result));
        captureInitialProviderStatus(claim, callAttemptId);
    }

    private void recordDispatchAccepted(UUID callJobId, UUID callAttemptId, ProviderDispatchResult result) {
        CallJob callJob = callJobRepository.findById(callJobId).orElseThrow();
        CallAttempt callAttempt = callAttemptRepository.findById(callAttemptId).orElseThrow();
        OperationContact contact = callJob.getOperationContact();
        OffsetDateTime now = OffsetDateTime.now();

        callAttempt.setProviderCallId(result.providerCallId());
        callAttempt.setDialedAt(result.occurredAt() != null ? result.occurredAt() : now);
        callAttempt.setFailureReason(trimToMaxLength(result.errorMessage(), CALL_ATTEMPT_FAILURE_REASON_MAX_LENGTH));
        callAttempt.setRawProviderPayload(result.rawPayload() != null ? result.rawPayload() : "{}");
        callJob.setLastErrorCode(result.errorCode());
        callJob.setLastErrorMessage(result.errorMessage());
        // A fast webhook or tool call may already have moved the attempt on; never move it back.
        if (callAttempt.getStatus() == CallAttemptStatus.INITIATED) {
            callAttempt.setStatus(result.attemptStatus() != null ? result.attemptStatus() : CallAttemptStatus.INITIATED);
            if (CallStatusSnapshotApplier.isCurrentAttempt(callJob, callAttempt)) {
                callRetryPolicy.applyReportedStatus(callJob, contact, result.jobStatus(), callAttempt.getStatus(), now);
            }
        }
        contact.setLastCallAt(callAttempt.getDialedAt());

        callAttemptRepository.save(callAttempt);
        callJobRepository.save(callJob);
        operationContactRepository.save(contact);
        providerExecutionObservationService.recordDispatchAccepted(callJob, callAttempt, result);

        log.info(
                "Dispatch result recorded. provider={} operationId={} callJobId={} callAttemptId={} providerCallId={} dispatchAt={} jobStatus={} attemptStatus={}",
                result.provider(),
                callJob.getOperation().getId(),
                callJob.getId(),
                callAttempt.getId(),
                result.providerCallId(),
                callAttempt.getDialedAt(),
                callJob.getStatus(),
                callAttempt.getStatus()
        );
    }

    private void recordDispatchFailure(
            UUID callJobId,
            UUID callAttemptId,
            CallProvider provider,
            RuntimeException error,
            boolean retryable
    ) {
        CallJob callJob = callJobRepository.findById(callJobId).orElseThrow();
        CallAttempt callAttempt = callAttemptRepository.findById(callAttemptId).orElseThrow();
        OperationContact contact = callJob.getOperationContact();
        OffsetDateTime now = OffsetDateTime.now();

        callJob.setLastErrorCode("DISPATCH_ERROR");
        callJob.setLastErrorMessage(error.getMessage());
        callAttempt.setStatus(CallAttemptStatus.FAILED);
        callAttempt.setDialedAt(now);
        callAttempt.setFailureReason(trimToMaxLength(error.getMessage(), CALL_ATTEMPT_FAILURE_REASON_MAX_LENGTH));
        callAttempt.setRawProviderPayload("{\"dispatchError\":true}");
        contact.setLastCallAt(now);
        callRetryPolicy.applyDispatchFailure(callJob, contact, retryable, now);

        callAttemptRepository.save(callAttempt);
        callJobRepository.save(callJob);
        operationContactRepository.save(contact);
        providerExecutionObservationService.recordDispatchFailed(
                callJob,
                callAttempt,
                provider,
                now,
                error.getMessage(),
                callAttempt.getRawProviderPayload()
        );

        log.warn(
                "Dispatch failure recorded. provider={} operationId={} callJobId={} callAttemptId={} retryable={} jobStatus={} failureReason={}",
                provider,
                callJob.getOperation().getId(),
                callJob.getId(),
                callAttempt.getId(),
                retryable,
                callJob.getStatus(),
                error.getMessage()
        );
    }

    private void captureInitialProviderStatus(DispatchClaim claim, UUID callAttemptId) {
        if (claim.configuration().mockMode()) {
            return;
        }
        CallAttempt callAttempt = transactionTemplate.execute(status -> {
            CallAttempt loaded = callAttemptRepository.findById(callAttemptId).orElse(null);
            if (loaded != null) {
                Hibernate.initialize(loaded.getCallJob());
            }
            return loaded;
        });
        if (callAttempt == null || callAttempt.getProviderCallId() == null || callAttempt.getProviderCallId().isBlank()) {
            return;
        }

        ProviderCallStatusResult statusResult;
        try {
            statusResult = claim.provider().fetchCallStatus(new ProviderCallStatusRequest(callAttempt), claim.configuration());
        } catch (RuntimeException error) {
            transactionTemplate.executeWithoutResult(status -> callAttemptRepository.findById(callAttemptId).ifPresent(current ->
                    providerExecutionObservationService.recordStatusSnapshotFailure(
                            current.getCallJob(),
                            current,
                            claim.provider().getProvider(),
                            error.getMessage()
                    )));
            log.warn(
                    "Initial provider status snapshot failed. provider={} callAttemptId={} providerCallId={} message={}",
                    claim.provider().getProvider(),
                    callAttemptId,
                    callAttempt.getProviderCallId(),
                    error.getMessage()
            );
            return;
        }

        transactionTemplate.executeWithoutResult(status -> callAttemptRepository.findById(callAttemptId).ifPresent(current -> {
            providerExecutionObservationService.recordStatusSnapshot(
                    current.getCallJob(),
                    current,
                    claim.provider().getProvider(),
                    statusResult,
                    "Initial provider status snapshot captured after dispatch acceptance"
            );
            callStatusSnapshotApplier.apply(current, statusResult);
            log.info(
                    "Initial provider status snapshot. provider={} callJobId={} callAttemptId={} providerCallId={} jobStatus={} attemptStatus={}",
                    claim.provider().getProvider(),
                    current.getCallJob().getId(),
                    current.getId(),
                    current.getProviderCallId(),
                    statusResult.jobStatus(),
                    statusResult.attemptStatus()
            );
        }));
    }

    private ActiveProvider resolveActiveProvider() {
        VoiceProviderConfiguration configuration = configurationResolver.getActiveConfiguration();
        VoiceExecutionProvider provider = callProviderRegistry.getRequiredProvider(configuration.provider());
        ProviderConfigurationValidationResult validation = provider.validateConfiguration(configuration);
        if (!validation.valid()) {
            throw new ValidationException(validation.message());
        }
        return new ActiveProvider(provider, configuration);
    }

    private CallAttempt createPendingAttempt(CallJob callJob, CallProvider provider, int attemptNumber) {
        CallAttempt callAttempt = new CallAttempt();
        callAttempt.setCompany(callJob.getCompany());
        callAttempt.setCallJob(callJob);
        callAttempt.setOperation(callJob.getOperation());
        callAttempt.setOperationContact(callJob.getOperationContact());
        callAttempt.setAttemptNumber(attemptNumber);
        callAttempt.setProvider(provider);
        callAttempt.setStatus(CallAttemptStatus.INITIATED);
        callAttempt.setRawProviderPayload("{}");
        return callAttemptRepository.save(callAttempt);
    }

    private String trimToMaxLength(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record ActiveProvider(VoiceExecutionProvider provider, VoiceProviderConfiguration configuration) {
    }

    private record DispatchClaim(
            VoiceExecutionProvider provider,
            VoiceProviderConfiguration configuration,
            ProviderDispatchRequest request
    ) {
    }
}
