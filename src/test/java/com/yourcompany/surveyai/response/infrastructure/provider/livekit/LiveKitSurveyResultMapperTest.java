package com.yourcompany.surveyai.response.infrastructure.provider.livekit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.surveyai.call.application.provider.ProviderWebhookEvent;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import com.yourcompany.surveyai.response.application.model.IngestedSurveyResult;
import com.yourcompany.surveyai.response.domain.enums.SurveyResponseStatus;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class LiveKitSurveyResultMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LiveKitSurveyResultMapper mapper = new LiveKitSurveyResultMapper(objectMapper);

    @Test
    void map_addsTheTranscriptAndLeavesTheAnswersToTheInterview() throws Exception {
        CallAttempt attempt = new CallAttempt();
        attempt.setConnectedAt(OffsetDateTime.now().minusMinutes(2));
        OffsetDateTime endedAt = OffsetDateTime.now();
        String rawPayload = """
                {"event":"ended","room":"call-1","summary":"Kısa görüşme",
                 "transcript":[{"role":"agent","text":"Merhaba"},{"role":"user","text":"Alo"}]}
                """;

        IngestedSurveyResult result = mapper.map(attempt, event(CallJobStatus.COMPLETED, CallAttemptStatus.COMPLETED,
                "agent: Merhaba\nuser: Alo", endedAt, rawPayload));

        assertThat(result.responseStatus()).isEqualTo(SurveyResponseStatus.PARTIAL);
        assertThat(result.answers()).isEmpty();
        assertThat(result.transcriptText()).isEqualTo("agent: Merhaba\nuser: Alo");
        assertThat(objectMapper.readTree(result.transcriptJson()).path("transcript")).hasSize(2);
        assertThat(result.aiSummaryText()).isEqualTo("Kısa görüşme");
        assertThat(result.startedAt()).isEqualTo(attempt.getConnectedAt());
        assertThat(result.completedAt()).isEqualTo(endedAt);
    }

    @Test
    void map_abandonsACallThatFailedBeforeAnyoneSpoke() {
        IngestedSurveyResult result = mapper.map(new CallAttempt(), event(CallJobStatus.FAILED, CallAttemptStatus.FAILED,
                null, OffsetDateTime.now(), "{\"event\":\"failed\",\"sipStatusCode\":404}"));

        assertThat(result.responseStatus()).isEqualTo(SurveyResponseStatus.ABANDONED);
        assertThat(result.transcriptText()).isNull();
    }

    private ProviderWebhookEvent event(
            CallJobStatus jobStatus,
            CallAttemptStatus attemptStatus,
            String transcriptText,
            OffsetDateTime occurredAt,
            String rawPayload
    ) {
        return new ProviderWebhookEvent(CallProvider.LIVEKIT, "call-1", null, "ended", jobStatus, attemptStatus, occurredAt,
                null, null, null, null, null, transcriptText, rawPayload);
    }
}
