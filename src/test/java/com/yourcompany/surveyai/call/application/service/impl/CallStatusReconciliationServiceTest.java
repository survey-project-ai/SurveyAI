package com.yourcompany.surveyai.call.application.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yourcompany.surveyai.call.application.provider.CallProviderRegistry;
import com.yourcompany.surveyai.call.application.provider.ProviderCallStatusRequest;
import com.yourcompany.surveyai.call.application.provider.ProviderCallStatusResult;
import com.yourcompany.surveyai.call.application.provider.ProviderCancelResult;
import com.yourcompany.surveyai.call.application.provider.ProviderConfigurationValidationResult;
import com.yourcompany.surveyai.call.application.provider.ProviderDispatchResult;
import com.yourcompany.surveyai.call.application.provider.ProviderWebhookEvent;
import com.yourcompany.surveyai.call.application.provider.ProviderWebhookRequest;
import com.yourcompany.surveyai.call.application.provider.VoiceExecutionProvider;
import com.yourcompany.surveyai.call.application.service.CallJobDispatcher;
import com.yourcompany.surveyai.call.application.service.ProviderExecutionObservationService;
import com.yourcompany.surveyai.call.application.support.CallRetryPolicy;
import com.yourcompany.surveyai.call.application.support.CallStatusSnapshotApplier;
import com.yourcompany.surveyai.call.configuration.CallQueueProperties;
import com.yourcompany.surveyai.call.configuration.VoiceExecutionProperties;
import com.yourcompany.surveyai.call.configuration.VoiceProviderConfigurationResolver;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import com.yourcompany.surveyai.call.repository.CallAttemptRepository;
import com.yourcompany.surveyai.call.repository.CallJobRepository;
import com.yourcompany.surveyai.common.domain.entity.Company;
import com.yourcompany.surveyai.operation.domain.entity.Operation;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.domain.enums.OperationContactStatus;
import com.yourcompany.surveyai.operation.repository.OperationContactRepository;
import com.yourcompany.surveyai.survey.domain.entity.Survey;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class CallStatusReconciliationServiceTest {

    private final CallAttemptRepository callAttemptRepository = mock(CallAttemptRepository.class);
    private final CallJobRepository callJobRepository = mock(CallJobRepository.class);
    private final OperationContactRepository operationContactRepository = mock(OperationContactRepository.class);
    private final ProviderExecutionObservationService providerExecutionObservationService = mock(ProviderExecutionObservationService.class);

    private final CallJobDispatcher callJobDispatcher = mock(CallJobDispatcher.class);

    @Test
    void reconcileOpenAttempts_schedulesRetryWhenProviderReportsNoAnswer() {
        CallAttempt attempt = buildAttempt();
        mockOpenAttempt(attempt);

        createService().reconcileOpenAttempts();

        assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.NO_ANSWER);
        assertThat(attempt.getEndedAt()).isNotNull();
        assertThat(attempt.getCallJob().getStatus()).isEqualTo(CallJobStatus.RETRY);
        assertThat(attempt.getCallJob().getAvailableAt()).isAfter(OffsetDateTime.now().plusMinutes(110));
        assertThat(attempt.getOperationContact().getStatus()).isEqualTo(OperationContactStatus.RETRY);
        verify(callJobDispatcher).requestDispatch(attempt.getOperation().getId());
    }

    @Test
    void reconcileOpenAttempts_failsJobWhenLastAttemptIsNotAnswered() {
        CallAttempt attempt = buildAttempt();
        attempt.setAttemptNumber(3);
        attempt.getCallJob().setAttemptCount(3);
        mockOpenAttempt(attempt);

        createService().reconcileOpenAttempts();

        assertThat(attempt.getCallJob().getStatus()).isEqualTo(CallJobStatus.FAILED);
        assertThat(attempt.getOperationContact().getStatus()).isEqualTo(OperationContactStatus.FAILED);
    }

    @Test
    void reconcileOpenAttempts_leavesAttemptAloneWhenAWebhookAlreadyFinishedIt() {
        CallAttempt listedAttempt = buildAttempt();
        mockOpenAttempt(listedAttempt);
        // Between the query and the update, the post-call webhook completed the interview.
        listedAttempt.setStatus(CallAttemptStatus.COMPLETED);
        listedAttempt.getCallJob().setStatus(CallJobStatus.COMPLETED);

        createService().reconcileOpenAttempts();

        assertThat(listedAttempt.getStatus()).isEqualTo(CallAttemptStatus.COMPLETED);
        assertThat(listedAttempt.getCallJob().getStatus()).isEqualTo(CallJobStatus.COMPLETED);
        verify(callJobDispatcher, never()).requestDispatch(any());
    }

    private void mockOpenAttempt(CallAttempt attempt) {
        when(callAttemptRepository.findTop100ByProviderAndStatusInAndProviderCallIdIsNotNullAndDeletedAtIsNullAndDialedAtBeforeOrderByDialedAtAsc(
                any(), any(), any()
        )).thenReturn(List.of(attempt));
        when(callAttemptRepository.findByIdAndDeletedAtIsNull(attempt.getId())).thenReturn(Optional.of(attempt));
        when(callAttemptRepository.save(any(CallAttempt.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(callJobRepository.save(any(CallJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(operationContactRepository.save(any(OperationContact.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private CallStatusReconciliationService createService() {
        VoiceExecutionProperties properties = new VoiceExecutionProperties();
        properties.setActiveProvider(CallProvider.ELEVENLABS);
        properties.getElevenlabs().setEnabled(true);
        properties.getElevenlabs().setMode(com.yourcompany.surveyai.call.configuration.VoiceProviderMode.LIVE);
        properties.getElevenlabs().setBaseUrl("https://api.elevenlabs.io");
        properties.getElevenlabs().setApiKey("test-key");
        properties.getElevenlabs().setAgentId("agent");
        properties.getElevenlabs().setPhoneNumberId("phone");

        return new CallStatusReconciliationService(
                new CallProviderRegistry(List.of(new ReconciliationProvider())),
                new VoiceProviderConfigurationResolver(properties),
                callAttemptRepository,
                providerExecutionObservationService,
                new CallStatusSnapshotApplier(
                        callJobRepository,
                        callAttemptRepository,
                        operationContactRepository,
                        new CallRetryPolicy(new CallQueueProperties())
                ),
                callJobDispatcher,
                mock(PlatformTransactionManager.class)
        );
    }

    private CallAttempt buildAttempt() {
        Company company = new Company();
        company.setId(UUID.randomUUID());

        Survey survey = new Survey();
        survey.setId(UUID.randomUUID());
        survey.setCompany(company);

        Operation operation = new Operation();
        operation.setId(UUID.randomUUID());
        operation.setCompany(company);
        operation.setSurvey(survey);

        OperationContact contact = new OperationContact();
        contact.setId(UUID.randomUUID());
        contact.setCompany(company);
        contact.setOperation(operation);
        contact.setStatus(OperationContactStatus.CALLING);
        contact.setPhoneNumber("905551112233");
        contact.setRetryCount(0);
        contact.setMetadataJson("{}");

        CallJob job = new CallJob();
        job.setId(UUID.randomUUID());
        job.setCompany(company);
        job.setOperation(operation);
        job.setOperationContact(contact);
        job.setStatus(CallJobStatus.QUEUED);
        job.setPriority((short) 1);
        job.setScheduledFor(OffsetDateTime.now().minusMinutes(1));
        job.setAvailableAt(OffsetDateTime.now().minusMinutes(1));
        job.setAttemptCount(1);
        job.setMaxAttempts(3);
        job.setIdempotencyKey("job:" + UUID.randomUUID());

        CallAttempt attempt = new CallAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setCompany(company);
        attempt.setCallJob(job);
        attempt.setOperation(operation);
        attempt.setOperationContact(contact);
        attempt.setAttemptNumber(1);
        attempt.setProvider(CallProvider.ELEVENLABS);
        attempt.setProviderCallId("conv-no-answer");
        attempt.setStatus(CallAttemptStatus.RINGING);
        attempt.setDialedAt(OffsetDateTime.now().minusMinutes(1));
        attempt.setRawProviderPayload("{}");
        return attempt;
    }

    private static class ReconciliationProvider implements VoiceExecutionProvider {

        @Override
        public CallProvider getProvider() {
            return CallProvider.ELEVENLABS;
        }

        @Override
        public ProviderDispatchResult dispatchCallJob(com.yourcompany.surveyai.call.application.provider.ProviderDispatchRequest request, com.yourcompany.surveyai.call.configuration.VoiceProviderConfiguration configuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderCallStatusResult fetchCallStatus(ProviderCallStatusRequest request, com.yourcompany.surveyai.call.configuration.VoiceProviderConfiguration configuration) {
            return new ProviderCallStatusResult(
                    CallJobStatus.FAILED,
                    CallAttemptStatus.NO_ANSWER,
                    OffsetDateTime.now(),
                    "{\"status\":\"failed\",\"failure_reason\":\"no-answer\"}"
            );
        }

        @Override
        public ProviderCancelResult cancelCall(com.yourcompany.surveyai.call.application.provider.ProviderCancelRequest request, com.yourcompany.surveyai.call.configuration.VoiceProviderConfiguration configuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderConfigurationValidationResult validateConfiguration(com.yourcompany.surveyai.call.configuration.VoiceProviderConfiguration configuration) {
            return ProviderConfigurationValidationResult.success();
        }

        @Override
        public boolean verifyWebhook(ProviderWebhookRequest request, com.yourcompany.surveyai.call.configuration.VoiceProviderConfiguration configuration) {
            return true;
        }

        @Override
        public List<ProviderWebhookEvent> parseWebhook(ProviderWebhookRequest request, com.yourcompany.surveyai.call.configuration.VoiceProviderConfiguration configuration) {
            return List.of();
        }
    }
}
