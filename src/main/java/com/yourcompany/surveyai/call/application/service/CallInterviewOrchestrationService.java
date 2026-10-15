package com.yourcompany.surveyai.call.application.service;

import com.yourcompany.surveyai.call.application.dto.request.InterviewAnswerRequest;
import com.yourcompany.surveyai.call.application.dto.request.InterviewFinishRequest;
import com.yourcompany.surveyai.call.application.dto.request.InterviewSessionRequest;
import com.yourcompany.surveyai.call.application.dto.response.InterviewOrchestrationResponse;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import java.util.List;

public interface CallInterviewOrchestrationService {

    // Providers whose voice agents drive the interview through the tool API.
    List<CallProvider> TOOL_PROVIDERS = List.of(CallProvider.ELEVENLABS, CallProvider.LIVEKIT);

    InterviewOrchestrationResponse startInterview(InterviewSessionRequest request);

    InterviewOrchestrationResponse getCurrentQuestion(InterviewSessionRequest request);

    InterviewOrchestrationResponse submitAnswer(InterviewAnswerRequest request);

    // Called when submitAnswer failed: records the failed attempt and returns what the agent says next.
    InterviewOrchestrationResponse recoverFailedAnswer(InterviewAnswerRequest request);

    InterviewOrchestrationResponse finishInterview(InterviewFinishRequest request);
}
