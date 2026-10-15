package com.yourcompany.surveyai.call.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yourcompany.surveyai.call.application.dto.request.InterviewAnswerRequest;
import com.yourcompany.surveyai.call.application.dto.request.InterviewConversationSignal;
import com.yourcompany.surveyai.call.application.dto.request.InterviewSessionRequest;
import com.yourcompany.surveyai.call.application.dto.response.InterviewOrchestrationResponse;
import com.yourcompany.surveyai.call.application.service.CallInterviewOrchestrationService;
import com.yourcompany.surveyai.call.configuration.VoiceProviderConfigurationResolver;
import com.yourcompany.surveyai.call.configuration.VoiceProviderMode;
import com.yourcompany.surveyai.call.configuration.VoiceExecutionProperties;
import com.yourcompany.surveyai.common.exception.UnauthorizedException;
import com.yourcompany.surveyai.response.domain.enums.SurveyResponseStatus;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class InterviewToolControllerTest {

    private static final String TOOL_SECRET = "tool-secret";

    private final CallInterviewOrchestrationService orchestrationService = mock(CallInterviewOrchestrationService.class);

    private final VoiceExecutionProperties properties = new VoiceExecutionProperties();
    private InterviewToolController controller;

    @BeforeEach
    void setUp() {
        VoiceExecutionProperties.ProviderProperties elevenlabs = new VoiceExecutionProperties.ProviderProperties();
        elevenlabs.setEnabled(true);
        elevenlabs.setMode(VoiceProviderMode.LIVE);
        elevenlabs.setApiKey("api-key");
        elevenlabs.setAgentId("agent-id");
        elevenlabs.setPhoneNumberId("phone-id");
        elevenlabs.setWebhookSecret("webhook-secret");
        elevenlabs.setBaseUrl("https://api.elevenlabs.io");
        elevenlabs.setSettings(Map.of("tool-api-secret", TOOL_SECRET));
        properties.setElevenlabs(elevenlabs);

        VoiceProviderConfigurationResolver configurationResolver = new VoiceProviderConfigurationResolver(properties);
        controller = new InterviewToolController(orchestrationService, configurationResolver);
    }

    @Test
    void submitAnswer_recordsAFailedAnswerAndReturnsWhatToSayNext() {
        UUID callAttemptId = UUID.randomUUID();
        InterviewAnswerRequest request = new InterviewAnswerRequest(
                callAttemptId,
                "provider-call-id",
                null,
                "Partiye gore.",
                InterviewConversationSignal.ANSWER
        );
        InterviewOrchestrationResponse recoveryResponse = new InterviewOrchestrationResponse(
                callAttemptId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Izmir Belediye Secimleri",
                "Genel Secim Nabzi",
                null,
                SurveyResponseStatus.PARTIAL,
                false,
                false,
                "Milletvekili secimlerinde, genel secimlerde partiye gore mi yoksa milletvekili adaylarina gore mi oy kullaniyorsunuz?",
                null,
                null,
                2,
                6,
                2,
                6
        );

        when(orchestrationService.submitAnswer(request)).thenThrow(new IllegalStateException("tool failure"));
        when(orchestrationService.recoverFailedAnswer(request))
                .thenReturn(recoveryResponse);

        ResponseEntity<InterviewOrchestrationResponse> response = controller.submitAnswer(TOOL_SECRET, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo(recoveryResponse);
    }

    @Test
    void startInterview_acceptsTheLiveKitAgentSecret() {
        VoiceExecutionProperties.ProviderProperties livekit = new VoiceExecutionProperties.ProviderProperties();
        livekit.setSettings(Map.of("tool-api-secret", "livekit-tool-secret"));
        properties.setLivekit(livekit);
        when(orchestrationService.startInterview(any())).thenReturn(startedResponse());

        ResponseEntity<InterviewOrchestrationResponse> response =
                controller.startInterview("livekit-tool-secret", new InterviewSessionRequest(UUID.randomUUID(), null, null));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void startInterview_rejectsAnUnknownSecret() {
        VoiceExecutionProperties.ProviderProperties livekit = new VoiceExecutionProperties.ProviderProperties();
        livekit.setSettings(Map.of("tool-api-secret", "livekit-tool-secret"));
        properties.setLivekit(livekit);

        assertThatThrownBy(() -> controller.startInterview("webhook-secret", null)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> controller.startInterview(null, null)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void startInterview_acceptsTheElevenLabsWebhookSecretWhenItHasNoToolSecret() {
        properties.getElevenlabs().setSettings(Map.of());
        when(orchestrationService.startInterview(any())).thenReturn(startedResponse());

        ResponseEntity<InterviewOrchestrationResponse> response = controller.startInterview("webhook-secret", null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        // LiveKit has no such fallback: without its own secret configured, a blank one is never accepted.
        assertThatThrownBy(() -> controller.startInterview(" ", null)).isInstanceOf(UnauthorizedException.class);
    }

    private InterviewOrchestrationResponse startedResponse() {
        return new InterviewOrchestrationResponse(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Anket", "Operasyon", null, SurveyResponseStatus.PARTIAL, false, false,
                "Memnun musunuz?", null, null, 0, 2, 0, 2
        );
    }
}
