package com.yourcompany.surveyai.call.application.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.surveyai.call.application.provider.CallProviderRegistry;
import com.yourcompany.surveyai.call.application.service.CallJobDispatcher;
import com.yourcompany.surveyai.call.application.service.ProviderExecutionObservationService;
import com.yourcompany.surveyai.call.configuration.VoiceExecutionProperties;
import com.yourcompany.surveyai.call.configuration.VoiceProviderMode;
import com.yourcompany.surveyai.call.configuration.VoiceProviderConfigurationResolver;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import com.yourcompany.surveyai.call.infrastructure.provider.elevenlabs.ElevenLabsApiClient;
import com.yourcompany.surveyai.call.infrastructure.provider.elevenlabs.ElevenLabsVoiceExecutionProvider;
import com.yourcompany.surveyai.call.infrastructure.provider.mock.MockVoiceExecutionProvider;
import com.yourcompany.surveyai.call.repository.CallAttemptRepository;
import com.yourcompany.surveyai.call.repository.CallJobRepository;
import com.yourcompany.surveyai.common.domain.entity.Company;
import com.yourcompany.surveyai.common.exception.NotFoundException;
import com.yourcompany.surveyai.operation.application.support.OperationAutoEntityLexiconService;
import com.yourcompany.surveyai.response.application.service.SurveyResponseIngestionService;
import com.yourcompany.surveyai.operation.domain.entity.Operation;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.domain.enums.OperationContactStatus;
import com.yourcompany.surveyai.operation.repository.OperationContactRepository;
import com.yourcompany.surveyai.survey.domain.entity.Survey;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProviderWebhookIngestionServiceImplTest {

    private final CallAttemptRepository callAttemptRepository = mock(CallAttemptRepository.class);
    private final CallJobRepository callJobRepository = mock(CallJobRepository.class);
    private final OperationContactRepository operationContactRepository = mock(OperationContactRepository.class);
    private final SurveyResponseIngestionService surveyResponseIngestionService = mock(SurveyResponseIngestionService.class);
    private final ProviderExecutionObservationService providerExecutionObservationService = mock(ProviderExecutionObservationService.class);
    private final CallJobDispatcher callJobDispatcher = mock(CallJobDispatcher.class);
    private final OperationAutoEntityLexiconService operationAutoEntityLexiconService = new OperationAutoEntityLexiconService(new ObjectMapper(), null, null) {
        @Override
        public java.util.List<String> extractKeywords(String sourcePayloadJson) {
            return java.util.List.of();
        }
    };
    private ProviderWebhookIngestionServiceImpl ingestionService;

    @BeforeEach
    void setUp() {
        VoiceExecutionProperties properties = new VoiceExecutionProperties();
        properties.getMock().setEnabled(true);
        properties.getElevenlabs().setEnabled(true);
        properties.getElevenlabs().setMode(VoiceProviderMode.MOCK);
        properties.getElevenlabs().setBaseUrl("https://api.elevenlabs.io");
        properties.getElevenlabs().setAgentId("agent-123");

        ingestionService = createIngestionService(properties);

        when(callAttemptRepository.save(any(CallAttempt.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(callJobRepository.save(any(CallJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(operationContactRepository.save(any(OperationContact.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void ingest_updatesInternalStatusesFromProviderWebhook() {
        CallAttempt attempt = buildAttempt();
        when(callAttemptRepository.findByProviderAndProviderCallIdAndDeletedAtIsNull(CallProvider.MOCK, attempt.getProviderCallId()))
                .thenReturn(Optional.of(attempt));

        HttpServletRequest request = mock(HttpServletRequest.class);
        Enumeration<String> headerNames = Collections.enumeration(Collections.emptyList());
        when(request.getHeaderNames()).thenReturn(headerNames);

        int applied = ingestionService.ingest(
                CallProvider.MOCK,
                """
                {
                  "providerCallId": "%s",
                  "status": "COMPLETED",
                  "occurredAt": "%s",
                  "durationSeconds": 42
                }
                """.formatted(attempt.getProviderCallId(), OffsetDateTime.now()),
                request
        );

        assertThat(applied).isEqualTo(1);
        assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.COMPLETED);
        assertThat(attempt.getCallJob().getStatus()).isEqualTo(CallJobStatus.COMPLETED);
        assertThat(attempt.getOperationContact().getStatus()).isEqualTo(OperationContactStatus.COMPLETED);
        verify(callJobDispatcher).dispatchNextPreparedJob(attempt.getCallJob().getOperation().getId());
    }

    @Test
    void ingest_ignoresStaleWebhookThatWouldRegressCompletedAttempt() {
        CallAttempt attempt = buildAttempt();
        attempt.setStatus(CallAttemptStatus.COMPLETED);
        attempt.setConnectedAt(OffsetDateTime.now().minusMinutes(3));
        attempt.setEndedAt(OffsetDateTime.now().minusMinutes(1));
        attempt.getCallJob().setStatus(CallJobStatus.COMPLETED);
        attempt.getOperationContact().setStatus(OperationContactStatus.COMPLETED);
        attempt.setRawProviderPayload("{\"status\":\"COMPLETED\"}");

        when(callAttemptRepository.findByProviderAndProviderCallIdAndDeletedAtIsNull(CallProvider.MOCK, attempt.getProviderCallId()))
                .thenReturn(Optional.of(attempt));

        HttpServletRequest request = mock(HttpServletRequest.class);
        Enumeration<String> headerNames = Collections.enumeration(Collections.emptyList());
        when(request.getHeaderNames()).thenReturn(headerNames);

        int applied = ingestionService.ingest(
                CallProvider.MOCK,
                """
                {
                  "providerCallId": "%s",
                  "status": "IN_PROGRESS",
                  "occurredAt": "%s"
                }
                """.formatted(attempt.getProviderCallId(), OffsetDateTime.now().minusMinutes(2)),
                request
        );

        assertThat(applied).isZero();
        assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.COMPLETED);
        assertThat(attempt.getCallJob().getStatus()).isEqualTo(CallJobStatus.COMPLETED);
        verify(surveyResponseIngestionService, never()).ingest(any(), any());
    }

    @Test
    void ingest_matchesElevenLabsWebhookByCallAttemptIdWhenProviderCallIdIsMissing() {
        CallAttempt attempt = buildAttempt();
        attempt.setProvider(CallProvider.ELEVENLABS);
        attempt.setProviderCallId("conv_123");

        when(callAttemptRepository.findByIdAndDeletedAtIsNull(attempt.getId()))
                .thenReturn(Optional.of(attempt));

        HttpServletRequest request = mock(HttpServletRequest.class);
        Enumeration<String> headerNames = Collections.enumeration(Collections.emptyList());
        when(request.getHeaderNames()).thenReturn(headerNames);

        int applied = ingestionService.ingest(
                CallProvider.ELEVENLABS,
                """
                {
                  "type": "post_call_transcription",
                  "event_timestamp": "2026-04-01T10:00:00Z",
                  "data": {
                    "status": "done",
                    "conversation_initiation_client_data": {
                      "dynamic_variables": {
                        "call_attempt_id": "%s",
                        "call_job_id": "%s",
                        "idempotency_key": "%s"
                      }
                    },
                    "transcript": [
                      {"role": "agent", "message": "Hello"},
                      {"role": "user", "message": "Hi"}
                    ]
                  }
                }
                """.formatted(attempt.getId(), attempt.getCallJob().getId(), attempt.getCallJob().getIdempotencyKey()),
                request
        );

        assertThat(applied).isEqualTo(1);
        verify(surveyResponseIngestionService).ingest(any(), any());
        assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.COMPLETED);
    }

    @Test
    void ingest_doesNotPersistSurveyResultForBusyTranscriptWebhook() {
        CallAttempt attempt = buildAttempt();
        attempt.setProvider(CallProvider.ELEVENLABS);
        attempt.setProviderCallId("conv_busy");

        when(callAttemptRepository.findByIdAndDeletedAtIsNull(attempt.getId()))
                .thenReturn(Optional.of(attempt));

        HttpServletRequest request = mock(HttpServletRequest.class);
        Enumeration<String> headerNames = Collections.enumeration(Collections.emptyList());
        when(request.getHeaderNames()).thenReturn(headerNames);

        int applied = ingestionService.ingest(
                CallProvider.ELEVENLABS,
                """
                {
                  "type": "post_call_transcription",
                  "event_timestamp": "2026-04-10T10:00:00Z",
                  "data": {
                    "status": "done",
                    "metadata": {
                      "body": {
                        "call_status": "busy"
                      }
                    },
                    "conversation_initiation_client_data": {
                      "dynamic_variables": {
                        "call_attempt_id": "%s",
                        "call_job_id": "%s",
                        "idempotency_key": "%s"
                      }
                    },
                    "transcript": [
                      {"role": "agent", "message": "Alo?"}
                    ]
                  }
                }
                """.formatted(attempt.getId(), attempt.getCallJob().getId(), attempt.getCallJob().getIdempotencyKey()),
                request
        );

        assertThat(applied).isEqualTo(1);
        assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.BUSY);
        assertThat(attempt.getCallJob().getStatus()).isEqualTo(CallJobStatus.FAILED);
        assertThat(attempt.getOperationContact().getStatus()).isEqualTo(OperationContactStatus.FAILED);
        verify(surveyResponseIngestionService, never()).ingest(any(), any());
    }

    @Test
    void ingest_doesNotPersistSurveyResultForVoicemailTranscriptWebhook() {
        CallAttempt attempt = buildAttempt();
        attempt.setProvider(CallProvider.ELEVENLABS);
        attempt.setProviderCallId("conv_voicemail");

        when(callAttemptRepository.findByIdAndDeletedAtIsNull(attempt.getId()))
                .thenReturn(Optional.of(attempt));

        HttpServletRequest request = mock(HttpServletRequest.class);
        Enumeration<String> headerNames = Collections.enumeration(Collections.emptyList());
        when(request.getHeaderNames()).thenReturn(headerNames);

        int applied = ingestionService.ingest(
                CallProvider.ELEVENLABS,
                """
                {
                  "type": "post_call_transcription",
                  "event_timestamp": "2026-04-10T10:00:00Z",
                  "data": {
                    "status": "done",
                    "conversation_initiation_client_data": {
                      "dynamic_variables": {
                        "call_attempt_id": "%s",
                        "call_job_id": "%s",
                        "idempotency_key": "%s"
                      }
                    },
                    "transcript": [
                      {"role": "user", "message": "Please leave your message after the tone."},
                      {"role": "agent", "message": "Merhaba, anket icin ariyorum."}
                    ]
                  }
                }
                """.formatted(attempt.getId(), attempt.getCallJob().getId(), attempt.getCallJob().getIdempotencyKey()),
                request
        );

        assertThat(applied).isEqualTo(1);
        assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.VOICEMAIL);
        assertThat(attempt.getCallJob().getStatus()).isEqualTo(CallJobStatus.FAILED);
        assertThat(attempt.getOperationContact().getStatus()).isEqualTo(OperationContactStatus.FAILED);
        verify(surveyResponseIngestionService, never()).ingest(any(), any());
    }

    @Test
    void ingest_terminalWebhook_triggersTransitionToNextPreparedJob() {
        CallAttempt firstAttempt = buildAttempt();
        UUID operationId = firstAttempt.getCallJob().getOperation().getId();

        when(callAttemptRepository.findByProviderAndProviderCallIdAndDeletedAtIsNull(CallProvider.MOCK, firstAttempt.getProviderCallId()))
                .thenReturn(Optional.of(firstAttempt));

        HttpServletRequest request = mock(HttpServletRequest.class);
        Enumeration<String> headerNames = Collections.enumeration(Collections.emptyList());
        when(request.getHeaderNames()).thenReturn(headerNames);

        int applied = ingestionService.ingest(
                CallProvider.MOCK,
                """
                {
                  "providerCallId": "%s",
                  "status": "FAILED",
                  "occurredAt": "%s",
                  "durationSeconds": 18
                }
                """.formatted(firstAttempt.getProviderCallId(), OffsetDateTime.now()),
                request
        );

        assertThat(applied).isEqualTo(1);
        assertThat(firstAttempt.getCallJob().getStatus()).isEqualTo(CallJobStatus.FAILED);
        assertThat(firstAttempt.getOperationContact().getStatus()).isEqualTo(OperationContactStatus.FAILED);
        verify(callJobDispatcher).dispatchNextPreparedJob(operationId);
    }

    @Test
    void ingest_rejectsWebhookForDisabledProviderWithoutRecordingIt() {
        VoiceExecutionProperties properties = new VoiceExecutionProperties();
        properties.getMock().setEnabled(false);
        ProviderWebhookIngestionServiceImpl serviceWithMockDisabled = createIngestionService(properties);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeaderNames()).thenReturn(Collections.enumeration(Collections.emptyList()));

        assertThatThrownBy(() -> serviceWithMockDisabled.ingest(
                CallProvider.MOCK,
                """
                {
                  "providerCallId": "mock-call-123",
                  "status": "COMPLETED"
                }
                """,
                request
        )).isInstanceOf(NotFoundException.class);

        verify(providerExecutionObservationService, never()).recordWebhookReceived(any(), any(), any());
        verify(callAttemptRepository, never()).findByProviderAndProviderCallIdAndDeletedAtIsNull(any(), any());
    }

    @Test
    void ingest_doesNotApplyWebhookToAttemptOfAnotherProvider() {
        CallAttempt attempt = buildAttempt();
        attempt.setProvider(CallProvider.ELEVENLABS);
        attempt.setProviderCallId("conv_live");
        CallJob callJob = attempt.getCallJob();

        when(callJobRepository.findByIdempotencyKeyAndDeletedAtIsNull(callJob.getIdempotencyKey()))
                .thenReturn(Optional.of(callJob));
        when(callAttemptRepository.findTopByCallJob_IdAndDeletedAtIsNullOrderByAttemptNumberDesc(callJob.getId()))
                .thenReturn(Optional.of(attempt));

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeaderNames()).thenReturn(Collections.enumeration(Collections.emptyList()));

        int applied = ingestionService.ingest(
                CallProvider.MOCK,
                """
                {
                  "idempotencyKey": "%s",
                  "status": "COMPLETED",
                  "occurredAt": "%s",
                  "transcript": "forged transcript"
                }
                """.formatted(callJob.getIdempotencyKey(), OffsetDateTime.now()),
                request
        );

        assertThat(applied).isZero();
        assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.IN_PROGRESS);
        assertThat(callJob.getStatus()).isEqualTo(CallJobStatus.IN_PROGRESS);
        verify(surveyResponseIngestionService, never()).ingest(any(), any());
        verify(callJobDispatcher, never()).dispatchNextPreparedJob(any());
    }

    private ProviderWebhookIngestionServiceImpl createIngestionService(VoiceExecutionProperties properties) {
        return new ProviderWebhookIngestionServiceImpl(
                new CallProviderRegistry(java.util.List.of(
                        new MockVoiceExecutionProvider(new ObjectMapper()),
                        new ElevenLabsVoiceExecutionProvider(new ObjectMapper(), mock(ElevenLabsApiClient.class), operationAutoEntityLexiconService)
                )),
                new VoiceProviderConfigurationResolver(properties),
                callAttemptRepository,
                callJobRepository,
                operationContactRepository,
                surveyResponseIngestionService,
                providerExecutionObservationService,
                callJobDispatcher
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
        operation.setName("Webhook Test");

        OperationContact contact = new OperationContact();
        contact.setId(UUID.randomUUID());
        contact.setCompany(company);
        contact.setOperation(operation);
        contact.setPhoneNumber("905551112233");
        contact.setStatus(OperationContactStatus.CALLING);
        contact.setRetryCount(0);
        contact.setMetadataJson("{}");

        CallJob callJob = new CallJob();
        callJob.setId(UUID.randomUUID());
        callJob.setCompany(company);
        callJob.setOperation(operation);
        callJob.setOperationContact(contact);
        callJob.setStatus(CallJobStatus.IN_PROGRESS);
        callJob.setPriority((short) 5);
        callJob.setScheduledFor(OffsetDateTime.now());
        callJob.setAvailableAt(OffsetDateTime.now());
        callJob.setAttemptCount(1);
        callJob.setMaxAttempts(3);
        callJob.setIdempotencyKey(operation.getId() + ":" + contact.getId());

        CallAttempt attempt = new CallAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setCompany(company);
        attempt.setCallJob(callJob);
        attempt.setOperation(operation);
        attempt.setOperationContact(contact);
        attempt.setAttemptNumber(1);
        attempt.setProvider(CallProvider.MOCK);
        attempt.setProviderCallId("mock-call-123");
        attempt.setStatus(CallAttemptStatus.IN_PROGRESS);
        attempt.setRawProviderPayload("{}");
        return attempt;
    }
}
