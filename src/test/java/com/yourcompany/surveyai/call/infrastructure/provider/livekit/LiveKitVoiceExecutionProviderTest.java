package com.yourcompany.surveyai.call.infrastructure.provider.livekit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.surveyai.call.application.provider.ProviderCallStatusRequest;
import com.yourcompany.surveyai.call.application.provider.ProviderCallStatusResult;
import com.yourcompany.surveyai.call.application.provider.ProviderDispatchException;
import com.yourcompany.surveyai.call.application.provider.ProviderDispatchRequest;
import com.yourcompany.surveyai.call.application.provider.ProviderDispatchResult;
import com.yourcompany.surveyai.call.application.provider.ProviderWebhookEvent;
import com.yourcompany.surveyai.call.application.provider.ProviderWebhookRequest;
import com.yourcompany.surveyai.call.configuration.VoiceProviderConfiguration;
import com.yourcompany.surveyai.call.configuration.VoiceProviderMode;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import com.yourcompany.surveyai.common.domain.entity.Company;
import com.yourcompany.surveyai.operation.application.support.OperationAutoEntityLexiconService;
import com.yourcompany.surveyai.operation.domain.entity.Operation;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.domain.enums.OperationContactStatus;
import com.yourcompany.surveyai.survey.domain.entity.Survey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpClientErrorException;

class LiveKitVoiceExecutionProviderTest {

    private static final String WEBHOOK_SECRET = "livekit-webhook-secret";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LiveKitApiClient apiClient = mock(LiveKitApiClient.class);
    private final OperationAutoEntityLexiconService operationAutoEntityLexiconService = new OperationAutoEntityLexiconService(objectMapper, null, null) {
        @Override
        public List<String> extractKeywords(String sourcePayloadJson) {
            return List.of("Mansur Yavaş");
        }
    };
    private LiveKitVoiceExecutionProvider provider;

    @BeforeEach
    void setUp() {
        provider = new LiveKitVoiceExecutionProvider(objectMapper, apiClient, operationAutoEntityLexiconService);
    }

    @Test
    void dispatchCallJob_sendsTheAgentToARoomNamedAfterTheAttempt() throws Exception {
        ProviderDispatchRequest request = buildRequest();
        when(apiClient.createDispatch(anyString(), anyString(), any())).thenReturn("{\"id\":\"AD_123\"}");

        ProviderDispatchResult result = provider.dispatchCallJob(request, configuration(VoiceProviderMode.LIVE));

        String roomName = "call-" + request.callAttempt().getId();
        assertThat(result.provider()).isEqualTo(CallProvider.LIVEKIT);
        assertThat(result.providerCallId()).isEqualTo(roomName);
        assertThat(result.jobStatus()).isEqualTo(CallJobStatus.QUEUED);
        assertThat(result.attemptStatus()).isEqualTo(CallAttemptStatus.INITIATED);
        assertThat(result.rawPayload()).contains("AD_123");

        org.mockito.ArgumentCaptor<String> metadata = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(apiClient).createDispatch(eq(roomName), metadata.capture(), any());
        JsonNode sent = objectMapper.readTree(metadata.getValue());
        assertThat(sent.path("callAttemptId").asText()).isEqualTo(request.callAttempt().getId().toString());
        assertThat(sent.path("callJobId").asText()).isEqualTo(request.callJob().getId().toString());
        assertThat(sent.path("idempotencyKey").asText()).isEqualTo("operation:contact");
        assertThat(sent.path("phoneNumber").asText()).isEqualTo("+905551112233");
        assertThat(sent.path("sipTrunkId").asText()).isEqualTo("ST_trunk");
        assertThat(sent.path("languageCode").asText()).isEqualTo("tr");
        assertThat(sent.path("asrKeywords").toString()).contains("Mansur Yavaş");
    }

    @Test
    void dispatchCallJob_placesNoCallInMockMode() {
        ProviderDispatchResult result = provider.dispatchCallJob(buildRequest(), configuration(VoiceProviderMode.MOCK));

        assertThat(result.providerCallId()).startsWith("mock-");
        verifyNoInteractions(apiClient);
    }

    @Test
    void dispatchCallJob_retriesOnlyWhenLiveKitCertainlyCreatedNoDispatch() {
        ProviderDispatchRequest request = buildRequest();
        VoiceProviderConfiguration configuration = configuration(VoiceProviderMode.LIVE);
        when(apiClient.createDispatch(anyString(), anyString(), any()))
                .thenThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", null, null, null))
                .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "bad request", null, null, null));

        assertThatThrownBy(() -> provider.dispatchCallJob(request, configuration))
                .isInstanceOfSatisfying(ProviderDispatchException.class, error -> assertThat(error.isRetryable()).isTrue());
        assertThatThrownBy(() -> provider.dispatchCallJob(request, configuration))
                .isInstanceOfSatisfying(ProviderDispatchException.class, error -> assertThat(error.isRetryable()).isFalse());
    }

    @Test
    void fetchCallStatus_reportsNothingNewWhileTheRoomIsOpen() {
        CallAttempt attempt = buildRequest().callAttempt();
        attempt.setProviderCallId("call-" + attempt.getId());
        attempt.setDialedAt(OffsetDateTime.now().minusMinutes(5));
        when(apiClient.listRooms(eq(attempt.getProviderCallId()), any()))
                .thenReturn("{\"rooms\":[{\"name\":\"" + attempt.getProviderCallId() + "\",\"num_participants\":2}]}");

        ProviderCallStatusResult result = provider.fetchCallStatus(new ProviderCallStatusRequest(attempt), configuration(VoiceProviderMode.LIVE));

        assertThat(result.jobStatus()).isNull();
        assertThat(result.attemptStatus()).isNull();
    }

    @Test
    void fetchCallStatus_failsACallWhoseRoomClosedWithoutAFinalEvent() {
        CallAttempt attempt = buildRequest().callAttempt();
        attempt.setProviderCallId("call-" + attempt.getId());
        when(apiClient.listRooms(eq(attempt.getProviderCallId()), any())).thenReturn("{\"rooms\":[]}");
        VoiceProviderConfiguration configuration = configuration(VoiceProviderMode.LIVE);

        attempt.setDialedAt(OffsetDateTime.now().minusSeconds(5));
        ProviderCallStatusResult justDispatched = provider.fetchCallStatus(new ProviderCallStatusRequest(attempt), configuration);
        attempt.setDialedAt(OffsetDateTime.now().minusMinutes(5));
        ProviderCallStatusResult roomGone = provider.fetchCallStatus(new ProviderCallStatusRequest(attempt), configuration);

        assertThat(justDispatched.jobStatus()).isNull();
        assertThat(roomGone.jobStatus()).isEqualTo(CallJobStatus.FAILED);
        assertThat(roomGone.attemptStatus()).isEqualTo(CallAttemptStatus.FAILED);
    }

    @Test
    void validateConfiguration_requiresEverySecretTheAgentAndBackendShare() {
        assertThat(provider.validateConfiguration(configuration(VoiceProviderMode.LIVE)).valid()).isTrue();
        assertThat(provider.validateConfiguration(configuration(VoiceProviderMode.MOCK)).valid()).isTrue();

        for (String setting : List.of("api-secret", "tool-api-secret")) {
            VoiceProviderConfiguration complete = configuration(VoiceProviderMode.LIVE);
            Map<String, String> settings = new HashMap<>(complete.settings());
            settings.remove(setting);
            assertThat(provider.validateConfiguration(withSettings(complete, settings)).valid()).as(setting).isFalse();
        }
        VoiceProviderConfiguration complete = configuration(VoiceProviderMode.LIVE);
        assertThat(provider.validateConfiguration(new VoiceProviderConfiguration(CallProvider.LIVEKIT, true, VoiceProviderMode.LIVE,
                complete.apiKey(), complete.agentId(), " ", complete.webhookSecret(), complete.baseUrl(), false, 300L,
                complete.settings())).valid()).isFalse();
        assertThat(provider.validateConfiguration(new VoiceProviderConfiguration(CallProvider.LIVEKIT, true, VoiceProviderMode.LIVE,
                complete.apiKey(), complete.agentId(), complete.phoneNumberId(), null, complete.baseUrl(), false, 300L,
                complete.settings())).valid()).isFalse();
        assertThat(provider.validateConfiguration(new VoiceProviderConfiguration(CallProvider.LIVEKIT, false, VoiceProviderMode.LIVE,
                complete.apiKey(), complete.agentId(), complete.phoneNumberId(), complete.webhookSecret(), complete.baseUrl(), false, 300L,
                complete.settings())).valid()).isFalse();
    }

    @Test
    void verifyWebhook_acceptsOnlyRecentEventsSignedWithTheWebhookSecret() throws Exception {
        String body = "{\"event\":\"ringing\"}";
        VoiceProviderConfiguration configuration = configuration(VoiceProviderMode.LIVE);
        long now = Instant.now().getEpochSecond();

        assertThat(provider.verifyWebhook(signed(body, now, WEBHOOK_SECRET), configuration)).isTrue();
        assertThat(provider.verifyWebhook(signed(body, now, "another-secret"), configuration)).isFalse();
        assertThat(provider.verifyWebhook(signed(body, now - 3600, WEBHOOK_SECRET), configuration)).isFalse();
        assertThat(provider.verifyWebhook(new ProviderWebhookRequest(CallProvider.LIVEKIT, body, Map.of()), configuration)).isFalse();
        assertThat(provider.verifyWebhook(new ProviderWebhookRequest(CallProvider.LIVEKIT, body, Map.of()), configuration(VoiceProviderMode.MOCK)))
                .isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "486, BUSY",
            "603, BUSY",
            "480, NO_ANSWER",
            "408, NO_ANSWER",
            "404, FAILED",
            "503, FAILED"
    })
    void parseWebhook_tellsWhyTheCalleesPhoneDidNotAnswer(int sipStatusCode, CallAttemptStatus expected) {
        ProviderWebhookEvent event = parseSingle("""
                {"event":"failed","room":"call-1","sipStatusCode":%d,"sipStatus":"SIP error"}
                """.formatted(sipStatusCode));

        assertThat(event.attemptStatus()).isEqualTo(expected);
        assertThat(event.jobStatus()).isEqualTo(CallJobStatus.FAILED);
        assertThat(event.errorCode()).isEqualTo(String.valueOf(sipStatusCode));
        assertThat(event.errorMessage()).isEqualTo("SIP error");
    }

    @Test
    void parseWebhook_readsAnsweredAndEndedCalls() {
        UUID callAttemptId = UUID.randomUUID();
        ProviderWebhookEvent answered = parseSingle("""
                {"event":"answered","room":"call-%s","callAttemptId":"%s","timestamp":1792077482.5}
                """.formatted(callAttemptId, callAttemptId));
        ProviderWebhookEvent ended = parseSingle("""
                {"event":"ended","room":"call-%s","callAttemptId":"%s","durationSeconds":95,"reason":"callee hung up",
                 "transcript":[{"role":"agent","text":"Merhaba, iyi günler."},{"role":"user","text":"Alo, buyrun."}]}
                """.formatted(callAttemptId, callAttemptId));

        assertThat(answered.attemptStatus()).isEqualTo(CallAttemptStatus.IN_PROGRESS);
        assertThat(answered.jobStatus()).isEqualTo(CallJobStatus.IN_PROGRESS);
        assertThat(answered.providerCallId()).isEqualTo("call-" + callAttemptId);
        assertThat(answered.correlationMetadata().callAttemptId()).isEqualTo(callAttemptId);
        assertThat(answered.occurredAt().toInstant()).isEqualTo(Instant.ofEpochMilli(1792077482500L));

        assertThat(ended.attemptStatus()).isEqualTo(CallAttemptStatus.COMPLETED);
        assertThat(ended.jobStatus()).isEqualTo(CallJobStatus.COMPLETED);
        assertThat(ended.durationSeconds()).isEqualTo(95);
        assertThat(ended.transcriptText()).isEqualTo("agent: Merhaba, iyi günler.\nuser: Alo, buyrun.");
        assertThat(ended.transcriptStorageKey()).isEqualTo("inline://livekit/rooms/call-" + callAttemptId);
        // A hang-up after the callee answered is not a failure.
        assertThat(ended.errorMessage()).isNull();
    }

    @Test
    void parseWebhook_ignoresUnknownEvents() {
        assertThat(provider.parseWebhook(
                new ProviderWebhookRequest(CallProvider.LIVEKIT, "{\"event\":\"something_else\"}", Map.of()),
                configuration(VoiceProviderMode.LIVE)
        )).isEmpty();
    }

    @Test
    void apiBaseUrl_usesHttpsForTheProjectsWebSocketUrl() {
        assertThat(HttpLiveKitApiClient.apiBaseUrl("wss://survey.livekit.cloud/")).isEqualTo("https://survey.livekit.cloud");
        assertThat(HttpLiveKitApiClient.apiBaseUrl("ws://localhost:7880")).isEqualTo("http://localhost:7880");
        assertThat(HttpLiveKitApiClient.apiBaseUrl("https://survey.livekit.cloud")).isEqualTo("https://survey.livekit.cloud");
    }

    private ProviderWebhookEvent parseSingle(String body) {
        List<ProviderWebhookEvent> events = provider.parseWebhook(
                new ProviderWebhookRequest(CallProvider.LIVEKIT, body, Map.of()),
                configuration(VoiceProviderMode.LIVE)
        );
        assertThat(events).hasSize(1);
        return events.getFirst();
    }

    private ProviderWebhookRequest signed(String body, long timestamp, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        // Servlet containers may hand header names over in lower case.
        return new ProviderWebhookRequest(CallProvider.LIVEKIT, body, Map.of("x-surveyai-signature", List.of("t=" + timestamp + ",v1=" + signature)));
    }

    private VoiceProviderConfiguration configuration(VoiceProviderMode mode) {
        return new VoiceProviderConfiguration(
                CallProvider.LIVEKIT,
                true,
                mode,
                "api-key",
                "survey-agent",
                "ST_trunk",
                WEBHOOK_SECRET,
                "wss://survey.livekit.cloud",
                false,
                300L,
                Map.of("api-secret", "api-secret", "tool-api-secret", "tool-secret")
        );
    }

    private VoiceProviderConfiguration withSettings(VoiceProviderConfiguration configuration, Map<String, String> settings) {
        return new VoiceProviderConfiguration(configuration.provider(), configuration.enabled(), configuration.mode(),
                configuration.apiKey(), configuration.agentId(), configuration.phoneNumberId(), configuration.webhookSecret(),
                configuration.baseUrl(), configuration.sandboxMode(), configuration.webhookTimestampToleranceSeconds(), settings);
    }

    private ProviderDispatchRequest buildRequest() {
        Company company = new Company();
        company.setId(UUID.randomUUID());
        company.setName("Acme Research");

        Survey survey = new Survey();
        survey.setId(UUID.randomUUID());
        survey.setCompany(company);
        survey.setName("Seçim anketi");
        survey.setLanguageCode("tr");

        Operation operation = new Operation();
        operation.setId(UUID.randomUUID());
        operation.setCompany(company);
        operation.setSurvey(survey);
        operation.setName("Ekim araması");

        OperationContact contact = new OperationContact();
        contact.setId(UUID.randomUUID());
        contact.setCompany(company);
        contact.setOperation(operation);
        contact.setPhoneNumber("905551112233");
        contact.setStatus(OperationContactStatus.PENDING);
        contact.setRetryCount(0);
        contact.setMetadataJson("{}");

        CallJob callJob = new CallJob();
        callJob.setId(UUID.randomUUID());
        callJob.setCompany(company);
        callJob.setOperation(operation);
        callJob.setOperationContact(contact);
        callJob.setStatus(CallJobStatus.PENDING);
        callJob.setAttemptCount(0);
        callJob.setMaxAttempts(3);
        callJob.setIdempotencyKey("operation:contact");

        CallAttempt callAttempt = new CallAttempt();
        callAttempt.setId(UUID.randomUUID());
        callAttempt.setCompany(company);
        callAttempt.setCallJob(callJob);
        callAttempt.setOperation(operation);
        callAttempt.setOperationContact(contact);
        callAttempt.setAttemptNumber(1);
        callAttempt.setProvider(CallProvider.LIVEKIT);
        callAttempt.setStatus(CallAttemptStatus.INITIATED);
        callAttempt.setRawProviderPayload("{}");

        return new ProviderDispatchRequest(callAttempt, callJob, operation, contact, survey);
    }
}
