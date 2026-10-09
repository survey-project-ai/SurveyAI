package com.yourcompany.surveyai.call.application.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yourcompany.surveyai.call.application.provider.CallProviderRegistry;
import com.yourcompany.surveyai.call.application.provider.ProviderCallStatusRequest;
import com.yourcompany.surveyai.call.application.provider.ProviderCallStatusResult;
import com.yourcompany.surveyai.call.application.provider.ProviderCancelRequest;
import com.yourcompany.surveyai.call.application.provider.ProviderCancelResult;
import com.yourcompany.surveyai.call.application.provider.ProviderConfigurationValidationResult;
import com.yourcompany.surveyai.call.application.provider.ProviderDispatchException;
import com.yourcompany.surveyai.call.application.provider.ProviderDispatchRequest;
import com.yourcompany.surveyai.call.application.provider.ProviderDispatchResult;
import com.yourcompany.surveyai.call.application.provider.ProviderWebhookEvent;
import com.yourcompany.surveyai.call.application.provider.ProviderWebhookRequest;
import com.yourcompany.surveyai.call.application.provider.VoiceExecutionProvider;
import com.yourcompany.surveyai.call.application.service.ProviderExecutionObservationService;
import com.yourcompany.surveyai.call.application.support.CallRetryPolicy;
import com.yourcompany.surveyai.call.application.support.CallStatusSnapshotApplier;
import com.yourcompany.surveyai.call.application.support.CallingHoursPolicy;
import com.yourcompany.surveyai.call.configuration.CallQueueProperties;
import com.yourcompany.surveyai.call.configuration.VoiceExecutionProperties;
import com.yourcompany.surveyai.call.configuration.VoiceProviderConfiguration;
import com.yourcompany.surveyai.call.configuration.VoiceProviderConfigurationResolver;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import com.yourcompany.surveyai.call.repository.CallAttemptRepository;
import com.yourcompany.surveyai.call.repository.CallJobRepository;
import com.yourcompany.surveyai.common.domain.entity.Company;
import com.yourcompany.surveyai.common.exception.ValidationException;
import com.yourcompany.surveyai.operation.domain.entity.Operation;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.domain.enums.OperationContactStatus;
import com.yourcompany.surveyai.operation.domain.enums.OperationStatus;
import com.yourcompany.surveyai.operation.repository.OperationContactRepository;
import com.yourcompany.surveyai.operation.repository.OperationRepository;
import com.yourcompany.surveyai.survey.domain.entity.Survey;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CallJobDispatcherImplTest {

    private final CallJobRepository callJobRepository = mock(CallJobRepository.class);
    private final CallAttemptRepository callAttemptRepository = mock(CallAttemptRepository.class);
    private final OperationContactRepository operationContactRepository = mock(OperationContactRepository.class);
    private final OperationRepository operationRepository = mock(OperationRepository.class);
    private final ProviderExecutionObservationService providerExecutionObservationService = mock(ProviderExecutionObservationService.class);

    private final Map<UUID, CallAttempt> savedAttempts = new LinkedHashMap<>();
    private final CallQueueProperties queueProperties = new CallQueueProperties();
    private final StubProvider provider = new StubProvider();

    private CallJobDispatcherImpl dispatcher;
    private Operation operation;
    private CallJob callJob;

    @BeforeEach
    void setUp() {
        // Start and end at the same time means calls are allowed all day, so tests do not depend on the clock.
        queueProperties.getCallingHours().setStart(LocalTime.MIDNIGHT);
        queueProperties.getCallingHours().setEnd(LocalTime.MIDNIGHT);

        callJob = buildCallJob();
        operation = callJob.getOperation();

        when(operationRepository.findAndLockForDispatch(operation.getId())).thenReturn(Optional.of(operation));
        when(callJobRepository.countByOperation_IdAndStatusInAndDeletedAtIsNull(eq(operation.getId()), any())).thenReturn(0L);
        when(callJobRepository.findAllByOperation_IdAndStatusInAndUpdatedAtBeforeAndDeletedAtIsNull(eq(operation.getId()), any(), any()))
                .thenReturn(List.of());
        when(callJobRepository.findFirstByOperation_IdAndStatusInAndAvailableAtBeforeAndDeletedAtIsNullOrderByPriorityDescScheduledForAscCreatedAtAsc(
                eq(operation.getId()), any(), any()
        )).thenReturn(Optional.of(callJob), Optional.empty());
        when(callJobRepository.findById(callJob.getId())).thenReturn(Optional.of(callJob));
        when(callJobRepository.save(any(CallJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(operationContactRepository.save(any(OperationContact.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(callAttemptRepository.save(any(CallAttempt.class))).thenAnswer(invocation -> {
            CallAttempt attempt = invocation.getArgument(0);
            if (attempt.getId() == null) {
                attempt.setId(UUID.randomUUID());
            }
            savedAttempts.put(attempt.getId(), attempt);
            return attempt;
        });
        when(callAttemptRepository.findById(any(UUID.class)))
                .thenAnswer(invocation -> Optional.ofNullable(savedAttempts.get(invocation.<UUID>getArgument(0))));

        dispatcher = createDispatcher(true);
    }

    @Test
    void dispatchAvailableJobs_claimsJobAndRecordsAcceptedCall() {
        int attempted = dispatcher.dispatchAvailableJobs(operation.getId());

        assertThat(attempted).isEqualTo(1);
        assertThat(callJob.getStatus()).isEqualTo(CallJobStatus.QUEUED);
        assertThat(callJob.getAttemptCount()).isEqualTo(1);
        assertThat(callJob.getLockedBy()).isNotBlank();
        assertThat(callJob.getOperationContact().getStatus()).isEqualTo(OperationContactStatus.CALLING);
        assertThat(savedAttempts.values()).singleElement().satisfies(attempt -> {
            assertThat(attempt.getAttemptNumber()).isEqualTo(1);
            assertThat(attempt.getProviderCallId()).isEqualTo("conv-" + attempt.getId());
            assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.INITIATED);
        });
        // The attempt row exists before the provider is called, so its tool calls and webhooks can find it.
        assertThat(provider.attemptIdsSeenAtDispatch).containsExactlyElementsOf(savedAttempts.keySet());
    }

    @Test
    void dispatchAvailableJobs_failsJobWhenProviderRejectsCallPermanently() {
        provider.dispatchError = new ProviderDispatchException("ElevenLabs dispatch failed: invalid number", false);

        dispatcher.dispatchAvailableJobs(operation.getId());

        assertThat(callJob.getStatus()).isEqualTo(CallJobStatus.FAILED);
        assertThat(callJob.getLastErrorCode()).isEqualTo("DISPATCH_ERROR");
        assertThat(callJob.getOperationContact().getStatus()).isEqualTo(OperationContactStatus.FAILED);
        assertThat(savedAttempts.values()).singleElement()
                .satisfies(attempt -> assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.FAILED));
    }

    @Test
    void dispatchAvailableJobs_retriesSoonWhenProviderIsTemporarilyUnavailable() {
        provider.dispatchError = new ProviderDispatchException("ElevenLabs dispatch failed: rate limited", true);

        dispatcher.dispatchAvailableJobs(operation.getId());

        assertThat(callJob.getStatus()).isEqualTo(CallJobStatus.RETRY);
        assertThat(callJob.getAvailableAt())
                .isAfter(OffsetDateTime.now().plusMinutes(4))
                .isBefore(OffsetDateTime.now().plusMinutes(6));
        assertThat(callJob.getLockedBy()).isNull();
        assertThat(callJob.getOperationContact().getStatus()).isEqualTo(OperationContactStatus.RETRY);
    }

    @Test
    void dispatchAvailableJobs_treatsUnexpectedProviderErrorAsPermanentAndTruncatesReason() {
        String longMessage = "x".repeat(400);
        provider.dispatchError = new IllegalStateException(longMessage);

        dispatcher.dispatchAvailableJobs(operation.getId());

        assertThat(callJob.getStatus()).isEqualTo(CallJobStatus.FAILED);
        assertThat(callJob.getLastErrorMessage()).hasSize(400);
        assertThat(savedAttempts.values()).singleElement()
                .satisfies(attempt -> assertThat(attempt.getFailureReason()).isEqualTo(longMessage.substring(0, 255)));
    }

    @Test
    void dispatchAvailableJobs_appliesTerminalInitialStatusSnapshot() {
        provider.statusSnapshot = new ProviderCallStatusResult(
                CallJobStatus.FAILED,
                CallAttemptStatus.FAILED,
                OffsetDateTime.now(),
                "{\"status\":\"failed\"}"
        );

        dispatcher.dispatchAvailableJobs(operation.getId());

        assertThat(callJob.getStatus()).isEqualTo(CallJobStatus.FAILED);
        assertThat(callJob.getOperationContact().getStatus()).isEqualTo(OperationContactStatus.FAILED);
        assertThat(savedAttempts.values()).singleElement().satisfies(attempt -> {
            assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
            assertThat(attempt.getEndedAt()).isNotNull();
        });
    }

    @Test
    void dispatchAvailableJobs_doesNotCallOutsideCallingHours() {
        LocalTime nowInIstanbul = LocalTime.now(ZoneId.of("Europe/Istanbul"));
        queueProperties.getCallingHours().setStart(nowInIstanbul.plusHours(1));
        queueProperties.getCallingHours().setEnd(nowInIstanbul.plusHours(2));

        int attempted = dispatcher.dispatchAvailableJobs(operation.getId());

        assertThat(attempted).isZero();
        assertThat(callJob.getStatus()).isEqualTo(CallJobStatus.PENDING);
        assertThat(provider.attemptIdsSeenAtDispatch).isEmpty();
    }

    @Test
    void dispatchAvailableJobs_respectsConcurrencyLimitPerOperation() {
        when(callJobRepository.countByOperation_IdAndStatusInAndDeletedAtIsNull(eq(operation.getId()), any())).thenReturn(1L);

        int attempted = dispatcher.dispatchAvailableJobs(operation.getId());

        assertThat(attempted).isZero();
        assertThat(callJob.getStatus()).isEqualTo(CallJobStatus.PENDING);
        verify(callAttemptRepository, never()).save(any(CallAttempt.class));
    }

    @Test
    void dispatchAvailableJobs_skipsWhenAnotherDispatcherHoldsTheOperation() {
        when(operationRepository.findAndLockForDispatch(operation.getId())).thenReturn(Optional.empty());

        int attempted = dispatcher.dispatchAvailableJobs(operation.getId());

        assertThat(attempted).isZero();
        verify(callAttemptRepository, never()).save(any(CallAttempt.class));
    }

    @Test
    void dispatchAvailableJobs_skipsOperationThatIsNotRunning() {
        operation.setStatus(OperationStatus.PAUSED);

        int attempted = dispatcher.dispatchAvailableJobs(operation.getId());

        assertThat(attempted).isZero();
        assertThat(provider.attemptIdsSeenAtDispatch).isEmpty();
    }

    @Test
    void dispatchAvailableJobs_skipsWithoutClaimingWhenProviderCannotPlaceCalls() {
        dispatcher = createDispatcher(false);

        int attempted = dispatcher.dispatchAvailableJobs(operation.getId());

        assertThat(attempted).isZero();
        verify(operationRepository, never()).findAndLockForDispatch(any());
        assertThat(callJob.getStatus()).isEqualTo(CallJobStatus.PENDING);
    }

    @Test
    void dispatchAvailableJobs_expiresActiveJobWhoseStatusNeverArrived() {
        CallJob staleJob = buildCallJob();
        staleJob.setOperation(operation);
        staleJob.setStatus(CallJobStatus.IN_PROGRESS);
        staleJob.setAttemptCount(1);
        CallAttempt staleAttempt = new CallAttempt();
        staleAttempt.setId(UUID.randomUUID());
        staleAttempt.setCallJob(staleJob);
        staleAttempt.setAttemptNumber(1);
        staleAttempt.setStatus(CallAttemptStatus.IN_PROGRESS);
        when(callJobRepository.findAllByOperation_IdAndStatusInAndUpdatedAtBeforeAndDeletedAtIsNull(eq(operation.getId()), any(), any()))
                .thenReturn(List.of(staleJob), List.of());
        when(callAttemptRepository.findTopByCallJob_IdAndDeletedAtIsNullOrderByAttemptNumberDesc(staleJob.getId()))
                .thenReturn(Optional.of(staleAttempt));

        dispatcher.dispatchAvailableJobs(operation.getId());

        assertThat(staleJob.getStatus()).isEqualTo(CallJobStatus.FAILED);
        assertThat(staleJob.getLastErrorCode()).isEqualTo("STATUS_TIMEOUT");
        assertThat(staleAttempt.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
        assertThat(callJob.getStatus()).isEqualTo(CallJobStatus.QUEUED);
    }

    @Test
    void requestDispatch_waitsForTheCallerTransactionToCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            dispatcher.requestDispatch(operation.getId());

            verify(operationRepository, never()).findAndLockForDispatch(any());

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(operationRepository, atLeastOnce()).findAndLockForDispatch(operation.getId());
        assertThat(callJob.getStatus()).isEqualTo(CallJobStatus.QUEUED);
    }

    @Test
    void validateActiveProviderConfiguration_throwsWhenProviderCannotPlaceCalls() {
        dispatcher = createDispatcher(false);

        assertThatThrownBy(() -> dispatcher.validateActiveProviderConfiguration())
                .isInstanceOf(ValidationException.class)
                .hasMessage("Stub provider is disabled");
    }

    private CallJobDispatcherImpl createDispatcher(boolean providerValid) {
        provider.valid = providerValid;
        VoiceExecutionProperties properties = new VoiceExecutionProperties();
        properties.setActiveProvider(CallProvider.MOCK);
        properties.getMock().setEnabled(true);
        CallRetryPolicy retryPolicy = new CallRetryPolicy(queueProperties);

        return new CallJobDispatcherImpl(
                new CallProviderRegistry(List.of(provider)),
                new VoiceProviderConfigurationResolver(properties),
                callJobRepository,
                callAttemptRepository,
                operationContactRepository,
                operationRepository,
                providerExecutionObservationService,
                retryPolicy,
                new CallingHoursPolicy(queueProperties),
                new CallStatusSnapshotApplier(callJobRepository, callAttemptRepository, operationContactRepository, retryPolicy),
                queueProperties,
                mock(PlatformTransactionManager.class),
                Runnable::run
        );
    }

    private CallJob buildCallJob() {
        Company company = new Company();
        company.setId(UUID.randomUUID());
        company.setName("Acme Research");

        Survey survey = new Survey();
        survey.setId(UUID.randomUUID());
        survey.setCompany(company);

        Operation callOperation = new Operation();
        callOperation.setId(UUID.randomUUID());
        callOperation.setCompany(company);
        callOperation.setSurvey(survey);
        callOperation.setName("Dispatch Test");
        callOperation.setStatus(OperationStatus.RUNNING);

        OperationContact contact = new OperationContact();
        contact.setId(UUID.randomUUID());
        contact.setCompany(company);
        contact.setOperation(callOperation);
        contact.setPhoneNumber("905551112233");
        contact.setStatus(OperationContactStatus.PENDING);
        contact.setRetryCount(0);
        contact.setMetadataJson("{}");

        CallJob job = new CallJob();
        job.setId(UUID.randomUUID());
        job.setCompany(company);
        job.setOperation(callOperation);
        job.setOperationContact(contact);
        job.setStatus(CallJobStatus.PENDING);
        job.setPriority((short) 5);
        job.setScheduledFor(OffsetDateTime.now());
        job.setAvailableAt(OffsetDateTime.now());
        job.setAttemptCount(0);
        job.setMaxAttempts(3);
        job.setIdempotencyKey(callOperation.getId() + ":" + contact.getId());
        return job;
    }

    private static class StubProvider implements VoiceExecutionProvider {

        private final List<UUID> attemptIdsSeenAtDispatch = new java.util.ArrayList<>();
        private boolean valid = true;
        private RuntimeException dispatchError;
        private ProviderCallStatusResult statusSnapshot;

        @Override
        public CallProvider getProvider() {
            return CallProvider.MOCK;
        }

        @Override
        public ProviderDispatchResult dispatchCallJob(ProviderDispatchRequest request, VoiceProviderConfiguration configuration) {
            attemptIdsSeenAtDispatch.add(request.callAttempt().getId());
            if (dispatchError != null) {
                throw dispatchError;
            }
            return new ProviderDispatchResult(
                    CallProvider.MOCK,
                    "conv-" + request.callAttempt().getId(),
                    CallJobStatus.QUEUED,
                    CallAttemptStatus.INITIATED,
                    OffsetDateTime.now(),
                    null,
                    null,
                    "{\"status\":\"queued\"}"
            );
        }

        @Override
        public ProviderCallStatusResult fetchCallStatus(ProviderCallStatusRequest request, VoiceProviderConfiguration configuration) {
            if (statusSnapshot != null) {
                return statusSnapshot;
            }
            return new ProviderCallStatusResult(
                    request.callAttempt().getCallJob().getStatus(),
                    request.callAttempt().getStatus(),
                    OffsetDateTime.now(),
                    request.callAttempt().getRawProviderPayload()
            );
        }

        @Override
        public ProviderCancelResult cancelCall(ProviderCancelRequest request, VoiceProviderConfiguration configuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderConfigurationValidationResult validateConfiguration(VoiceProviderConfiguration configuration) {
            return valid
                    ? ProviderConfigurationValidationResult.success()
                    : ProviderConfigurationValidationResult.failure("Stub provider is disabled");
        }

        @Override
        public boolean verifyWebhook(ProviderWebhookRequest request, VoiceProviderConfiguration configuration) {
            return true;
        }

        @Override
        public List<ProviderWebhookEvent> parseWebhook(ProviderWebhookRequest request, VoiceProviderConfiguration configuration) {
            return List.of();
        }
    }
}
