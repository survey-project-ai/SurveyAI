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
import com.yourcompany.surveyai.call.application.support.CallRetryPolicy;
import com.yourcompany.surveyai.call.configuration.CallQueueProperties;
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
import com.yourcompany.surveyai.call.infrastructure.provider.livekit.LiveKitApiClient;
import com.yourcompany.surveyai.call.infrastructure.provider.livekit.LiveKitVoiceExecutionProvider;
import com.yourcompany.surveyai.call.infrastructure.provider.mock.MockVoiceExecutionProvider;
import com.yourcompany.surveyai.call.repository.CallAttemptRepository;
import com.yourcompany.surveyai.call.repository.CallJobRepository;
import com.yourcompany.surveyai.common.domain.entity.Company;
import com.yourcompany.surveyai.common.exception.NotFoundException;
import com.yourcompany.surveyai.common.exception.ValidationException;
import com.yourcompany.surveyai.operation.application.support.OperationAutoEntityLexiconService;
import com.yourcompany.surveyai.response.application.service.SurveyResponseIngestionService;
import com.yourcompany.surveyai.operation.domain.entity.Operation;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.domain.enums.OperationContactStatus;
import com.yourcompany.surveyai.operation.repository.OperationContactRepository;
import com.yourcompany.surveyai.survey.domain.entity.Survey;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
    private static final String LIVEKIT_WEBHOOK_SECRET = "livekit-webhook-secret";
    private ProviderWebhookIngestionServiceImpl ingestionService;

    @BeforeEach
    void setUp() {
        VoiceExecutionProperties properties = new VoiceExecutionProperties();
        properties.getMock().setEnabled(true);
        properties.getElevenlabs().setEnabled(true);
        properties.getElevenlabs().setMode(VoiceProviderMode.MOCK);
        properties.getElevenlabs().setBaseUrl("https://api.elevenlabs.io");
        properties.getElevenlabs().setAgentId("agent-123");
        properties.getLivekit().setEnabled(true);
        properties.getLivekit().setMode(VoiceProviderMode.LIVE);
        properties.getLivekit().setWebhookSecret(LIVEKIT_WEBHOOK_SECRET);

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
        verify(callJobDispatcher).requestDispatch(attempt.getCallJob().getOperation().getId());
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
    void ingest_appliesPostCallWebhookAfterReconciliationAlreadyFinishedTheAttempt() {
        CallAttempt attempt = buildAttempt();
        attempt.setProvider(CallProvider.ELEVENLABS);
        attempt.setProviderCallId("conv_polled");
        // Reconciliation saw "done" first and recorded the poll time as the end.
        attempt.setStatus(CallAttemptStatus.COMPLETED);
        attempt.setConnectedAt(OffsetDateTime.parse("2026-04-10T09:58:00Z"));
        attempt.setEndedAt(OffsetDateTime.parse("2026-04-10T10:00:06Z"));
        attempt.setRawProviderPayload("{\"status\":\"done\"}");
        attempt.getCallJob().setStatus(CallJobStatus.COMPLETED);
        attempt.getOperationContact().setStatus(OperationContactStatus.COMPLETED);

        when(callAttemptRepository.findByIdAndDeletedAtIsNull(attempt.getId()))
                .thenReturn(Optional.of(attempt));

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeaderNames()).thenReturn(Collections.enumeration(Collections.emptyList()));

        int applied = ingestionService.ingest(
                CallProvider.ELEVENLABS,
                """
                {
                  "type": "post_call_transcription",
                  "event_timestamp": "2026-04-10T10:00:05Z",
                  "data": {
                    "conversation_id": "conv_polled",
                    "status": "done",
                    "conversation_initiation_client_data": {
                      "dynamic_variables": {
                        "call_attempt_id": "%s",
                        "call_job_id": "%s",
                        "idempotency_key": "%s"
                      }
                    },
                    "transcript": [
                      {"role": "user", "message": "Alo"},
                      {"role": "agent", "message": "Merhaba, kisa bir anket icin ariyorum."},
                      {"role": "user", "message": "Buyurun"},
                      {"role": "agent", "message": "Tesekkur ederim."}
                    ]
                  }
                }
                """.formatted(attempt.getId(), attempt.getCallJob().getId(), attempt.getCallJob().getIdempotencyKey()),
                request
        );

        assertThat(applied).isEqualTo(1);
        assertThat(attempt.getTranscriptStorageKey()).isEqualTo("inline://elevenlabs/conversations/conv_polled");
        assertThat(attempt.getEndedAt()).isEqualTo(OffsetDateTime.parse("2026-04-10T10:00:05Z"));
        verify(surveyResponseIngestionService).ingest(any(), any());
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
        // First of three attempts: the contact is called again later.
        assertThat(attempt.getCallJob().getStatus()).isEqualTo(CallJobStatus.RETRY);
        assertThat(attempt.getOperationContact().getStatus()).isEqualTo(OperationContactStatus.RETRY);
        verify(surveyResponseIngestionService, never()).ingest(any(), any());
        verify(callJobDispatcher).requestDispatch(attempt.getOperation().getId());
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
        assertThat(attempt.getCallJob().getStatus()).isEqualTo(CallJobStatus.RETRY);
        assertThat(attempt.getOperationContact().getStatus()).isEqualTo(OperationContactStatus.RETRY);
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
        verify(callJobDispatcher).requestDispatch(operationId);
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
        verify(callJobDispatcher, never()).requestDispatch(any());
    }

    @Test
    void ingest_lateWebhookForEarlierAttemptDoesNotChangeRetriedJob() {
        CallAttempt firstAttempt = buildAttempt();
        CallJob callJob = firstAttempt.getCallJob();
        // The job was retried and its second attempt is ringing now.
        callJob.setAttemptCount(2);
        callJob.setStatus(CallJobStatus.QUEUED);
        firstAttempt.getOperationContact().setStatus(OperationContactStatus.CALLING);
        when(callAttemptRepository.findByProviderAndProviderCallIdAndDeletedAtIsNull(CallProvider.MOCK, firstAttempt.getProviderCallId()))
                .thenReturn(Optional.of(firstAttempt));

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeaderNames()).thenReturn(Collections.enumeration(Collections.emptyList()));

        int applied = ingestionService.ingest(
                CallProvider.MOCK,
                """
                {
                  "providerCallId": "%s",
                  "status": "FAILED",
                  "occurredAt": "%s"
                }
                """.formatted(firstAttempt.getProviderCallId(), OffsetDateTime.now()),
                request
        );

        assertThat(applied).isEqualTo(1);
        assertThat(firstAttempt.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
        assertThat(callJob.getStatus()).isEqualTo(CallJobStatus.QUEUED);
        assertThat(firstAttempt.getOperationContact().getStatus()).isEqualTo(OperationContactStatus.CALLING);
        verify(callJobDispatcher, never()).requestDispatch(any());
    }

    @Test
    void ingest_schedulesAnotherCallWhenTheLiveKitAgentFindsTheLineBusy() throws Exception {
        CallAttempt attempt = buildAttempt();
        attempt.setProvider(CallProvider.LIVEKIT);
        // The agent can report before the dispatcher has stored the room name.
        attempt.setProviderCallId(null);
        attempt.setStatus(CallAttemptStatus.INITIATED);
        when(callAttemptRepository.findByIdAndDeletedAtIsNull(attempt.getId())).thenReturn(Optional.of(attempt));
        String body = """
                {"event":"failed","room":"call-%s","callAttemptId":"%s","sipStatusCode":486,"sipStatus":"Busy Here"}
                """.formatted(attempt.getId(), attempt.getId());

        int applied = ingestionService.ingest(CallProvider.LIVEKIT, body, signedRequest(body, LIVEKIT_WEBHOOK_SECRET));

        assertThat(applied).isEqualTo(1);
        assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.BUSY);
        assertThat(attempt.getProviderCallId()).isEqualTo("call-" + attempt.getId());
        assertThat(attempt.getCallJob().getStatus()).isEqualTo(CallJobStatus.RETRY);
        assertThat(attempt.getOperationContact().getStatus()).isEqualTo(OperationContactStatus.RETRY);
        verify(surveyResponseIngestionService, never()).ingest(any(), any());
    }

    @Test
    void ingest_addsTheTranscriptWhenTheLiveKitCallEnds() throws Exception {
        CallAttempt attempt = buildAttempt();
        attempt.setProvider(CallProvider.LIVEKIT);
        attempt.setProviderCallId("call-" + attempt.getId());
        when(callAttemptRepository.findByProviderAndProviderCallIdAndDeletedAtIsNull(CallProvider.LIVEKIT, attempt.getProviderCallId()))
                .thenReturn(Optional.of(attempt));
        String body = """
                {"event":"ended","room":"%s","durationSeconds":80,
                 "transcript":[{"role":"agent","text":"Merhaba"},{"role":"user","text":"Alo"}]}
                """.formatted(attempt.getProviderCallId());

        int applied = ingestionService.ingest(CallProvider.LIVEKIT, body, signedRequest(body, LIVEKIT_WEBHOOK_SECRET));

        assertThat(applied).isEqualTo(1);
        assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.COMPLETED);
        assertThat(attempt.getDurationSeconds()).isEqualTo(80);
        assertThat(attempt.getFailureReason()).isNull();
        assertThat(attempt.getTranscriptStorageKey()).isEqualTo("inline://livekit/rooms/" + attempt.getProviderCallId());
        assertThat(attempt.getCallJob().getStatus()).isEqualTo(CallJobStatus.COMPLETED);
        verify(surveyResponseIngestionService).ingest(any(), any());
    }

    @Test
    void ingest_rejectsALiveKitEventWithoutAValidSignature() throws Exception {
        String body = "{\"event\":\"ended\",\"room\":\"call-1\"}";

        assertThatThrownBy(() -> ingestionService.ingest(CallProvider.LIVEKIT, body, signedRequest(body, "wrong-secret")))
                .isInstanceOf(ValidationException.class);
        verify(callAttemptRepository, never()).save(any());
    }

    private HttpServletRequest signedRequest(String body, String secret) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeaderNames()).thenReturn(Collections.enumeration(java.util.List.of("x-surveyai-signature")));
        when(request.getHeaders("x-surveyai-signature"))
                .thenReturn(Collections.enumeration(java.util.List.of("t=" + timestamp + ",v1=" + signature)));
        return request;
    }

    private ProviderWebhookIngestionServiceImpl createIngestionService(VoiceExecutionProperties properties) {
        return new ProviderWebhookIngestionServiceImpl(
                new CallProviderRegistry(java.util.List.of(
                        new MockVoiceExecutionProvider(new ObjectMapper()),
                        new ElevenLabsVoiceExecutionProvider(new ObjectMapper(), mock(ElevenLabsApiClient.class), operationAutoEntityLexiconService),
                        new LiveKitVoiceExecutionProvider(new ObjectMapper(), mock(LiveKitApiClient.class), operationAutoEntityLexiconService)
                )),
                new VoiceProviderConfigurationResolver(properties),
                callAttemptRepository,
                callJobRepository,
                operationContactRepository,
                surveyResponseIngestionService,
                providerExecutionObservationService,
                callJobDispatcher,
                new CallRetryPolicy(new CallQueueProperties())
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
