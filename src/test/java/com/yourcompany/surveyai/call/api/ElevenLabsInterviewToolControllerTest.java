package com.yourcompany.surveyai.call.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yourcompany.surveyai.call.application.dto.request.InterviewAnswerRequest;
import com.yourcompany.surveyai.call.application.dto.request.InterviewConversationSignal;
import com.yourcompany.surveyai.call.application.dto.response.InterviewOrchestrationResponse;
import com.yourcompany.surveyai.call.application.service.CallInterviewOrchestrationService;
import com.yourcompany.surveyai.call.configuration.VoiceProviderConfigurationResolver;
import com.yourcompany.surveyai.call.configuration.VoiceProviderMode;
import com.yourcompany.surveyai.call.configuration.VoiceExecutionProperties;
import com.yourcompany.surveyai.response.domain.enums.SurveyResponseStatus;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class ElevenLabsInterviewToolControllerTest {

    private static final String TOOL_SECRET = "tool-secret";

    private final CallInterviewOrchestrationService orchestrationService = mock(CallInterviewOrchestrationService.class);

    private ElevenLabsInterviewToolController controller;

    @BeforeEach
    void setUp() {
        VoiceExecutionProperties properties = new VoiceExecutionProperties();
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
        controller = new ElevenLabsInterviewToolController(orchestrationService, configurationResolver);
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
}
