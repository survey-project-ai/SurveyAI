package com.yourcompany.surveyai.call.api;

import com.yourcompany.surveyai.call.application.dto.request.InterviewAnswerRequest;
import com.yourcompany.surveyai.call.application.dto.request.InterviewFinishRequest;
import com.yourcompany.surveyai.call.application.dto.request.InterviewSessionRequest;
import com.yourcompany.surveyai.call.application.dto.response.InterviewOrchestrationResponse;
import com.yourcompany.surveyai.call.application.service.CallInterviewOrchestrationService;
import com.yourcompany.surveyai.call.configuration.VoiceProviderConfiguration;
import com.yourcompany.surveyai.call.configuration.VoiceProviderConfigurationResolver;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import com.yourcompany.surveyai.common.exception.UnauthorizedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/provider-tools/elevenlabs/interviews")
public class ElevenLabsInterviewToolController {

    private static final String TOOL_SECRET_HEADER = "X-SurveyAI-Tool-Secret";
    private static final Logger log = LoggerFactory.getLogger(ElevenLabsInterviewToolController.class);

    private final CallInterviewOrchestrationService callInterviewOrchestrationService;
    private final VoiceProviderConfigurationResolver configurationResolver;

    public ElevenLabsInterviewToolController(
            CallInterviewOrchestrationService callInterviewOrchestrationService,
            VoiceProviderConfigurationResolver configurationResolver
    ) {
        this.callInterviewOrchestrationService = callInterviewOrchestrationService;
        this.configurationResolver = configurationResolver;
    }

    @PostMapping("/start")
    public ResponseEntity<InterviewOrchestrationResponse> startInterview(
            @RequestHeader(name = TOOL_SECRET_HEADER, required = false) String toolSecret,
            @RequestBody(required = false) InterviewSessionRequest request
    ) {
        validateToolSecret(toolSecret);
        InterviewSessionRequest effectiveRequest = request == null ? new InterviewSessionRequest(null, null, null) : request;
        InterviewOrchestrationResponse response = timedToolCall(
                "start",
                effectiveRequest.callAttemptId(),
                effectiveRequest.providerCallId(),
                () -> callInterviewOrchestrationService.startInterview(effectiveRequest)
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/current-question")
    public ResponseEntity<InterviewOrchestrationResponse> currentQuestion(
            @RequestHeader(name = TOOL_SECRET_HEADER, required = false) String toolSecret,
            @RequestBody(required = false) InterviewSessionRequest request
    ) {
        validateToolSecret(toolSecret);
        InterviewSessionRequest effectiveRequest = request == null ? new InterviewSessionRequest(null, null, null) : request;
        InterviewOrchestrationResponse response = timedToolCall(
                "current-question",
                effectiveRequest.callAttemptId(),
                effectiveRequest.providerCallId(),
                () -> callInterviewOrchestrationService.getCurrentQuestion(effectiveRequest)
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/answer")
    public ResponseEntity<InterviewOrchestrationResponse> submitAnswer(
            @RequestHeader(name = TOOL_SECRET_HEADER, required = false) String toolSecret,
            @RequestBody InterviewAnswerRequest request
    ) {
        validateToolSecret(toolSecret);
        InterviewOrchestrationResponse response;
        try {
            response = timedToolCall(
                    "answer",
                    request.callAttemptId(),
                    request.providerCallId(),
                    () -> callInterviewOrchestrationService.submitAnswer(request),
                    "signal=" + request.signal()
            );
        } catch (RuntimeException error) {
            response = recoverFailedAnswer(request, error);
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/finish")
    public ResponseEntity<InterviewOrchestrationResponse> finishInterview(
            @RequestHeader(name = TOOL_SECRET_HEADER, required = false) String toolSecret,
            @RequestBody(required = false) InterviewFinishRequest request
    ) {
        validateToolSecret(toolSecret);
        InterviewFinishRequest effectiveRequest = request == null ? new InterviewFinishRequest(null, null, null, null, null) : request;
        InterviewOrchestrationResponse response = timedToolCall(
                "finish",
                effectiveRequest.callAttemptId(),
                effectiveRequest.providerCallId(),
                () -> callInterviewOrchestrationService.finishInterview(effectiveRequest),
                "requestedStatus=" + effectiveRequest.requestedStatus(),
                "suppressClosingMessage=" + effectiveRequest.suppressClosingMessage()
        );
        return ResponseEntity.ok(response);
    }

    private InterviewOrchestrationResponse timedToolCall(
            String endpoint,
            Object callAttemptId,
            Object providerCallId,
            Supplier<InterviewOrchestrationResponse> action,
            String... extras
    ) {
        long startedAtNanos = System.nanoTime();
        log.info(
                "ElevenLabs tool hit. endpoint={} callAttemptId={} providerCallId={} {}",
                endpoint,
                callAttemptId,
                providerCallId,
                joinExtras(extras)
        );
        try {
            InterviewOrchestrationResponse response = action.get();
            long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
            log.info(
                    "ElevenLabs tool completed. endpoint={} callAttemptId={} providerCallId={} elapsedMs={} completed={} endCall={} questionId={} promptLength={} closingLength={} {}",
                    endpoint,
                    response.callAttemptId(),
                    providerCallId,
                    elapsedMs,
                    response.completed(),
                    response.endCall(),
                    response.question() == null ? null : response.question().id(),
                    response.prompt() == null ? 0 : response.prompt().length(),
                    response.closingMessage() == null ? 0 : response.closingMessage().length(),
                    joinExtras(extras)
            );
            return response;
        } catch (RuntimeException error) {
            long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
            log.warn(
                    "ElevenLabs tool failed. endpoint={} callAttemptId={} providerCallId={} elapsedMs={} error={} {}",
                    endpoint,
                    callAttemptId,
                    providerCallId,
                    elapsedMs,
                    error.getMessage(),
                    joinExtras(extras)
            );
            throw error;
        }
    }

    private String joinExtras(String... extras) {
        if (extras == null || extras.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String extra : extras) {
            if (extra == null || extra.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(extra);
        }
        return builder.toString();
    }

    // The agent cannot act on a server error mid-call, so a failed answer is recorded as an unclear
    // answer and the agent gets the next thing to say. The failure is logged with its stack trace,
    // since it is otherwise invisible: the call goes on.
    private InterviewOrchestrationResponse recoverFailedAnswer(InterviewAnswerRequest request, RuntimeException originalError) {
        log.error(
                "ElevenLabs answer tool failed; recording it as an unclear answer. callAttemptId={} providerCallId={} signal={}",
                request.callAttemptId(),
                request.providerCallId(),
                request.signal(),
                originalError
        );
        try {
            return timedToolCall(
                    "answer-recovery",
                    request.callAttemptId(),
                    request.providerCallId(),
                    () -> callInterviewOrchestrationService.recoverFailedAnswer(request),
                    "signal=" + request.signal()
            );
        } catch (RuntimeException recoveryError) {
            recoveryError.addSuppressed(originalError);
            throw recoveryError;
        }
    }

    private void validateToolSecret(String providedSecret) {
        VoiceProviderConfiguration configuration = configurationResolver.getConfiguration(CallProvider.ELEVENLABS);
        String expectedSecret = configuration.settings().get("tool-api-secret");
        if (expectedSecret == null || expectedSecret.isBlank()) {
            expectedSecret = configuration.webhookSecret();
        }

        if (expectedSecret == null || expectedSecret.isBlank() || !expectedSecret.equals(providedSecret)) {
            log.warn(
                    "ElevenLabs tool authentication failed. expectedSecretPresent={} expectedSecretFingerprint={} providedSecretPresent={} providedSecretPreview={} providedSecretFingerprint={}",
                    hasText(expectedSecret),
                    fingerprint(expectedSecret),
                    hasText(providedSecret),
                    preview(providedSecret),
                    fingerprint(providedSecret)
            );
            throw new UnauthorizedException("Provider tool authentication failed");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String preview(String value) {
        if (!hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= 8
                ? trimmed.charAt(0) + "***"
                : trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4);
    }

    private String fingerprint(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (Exception ignored) {
            return "unavailable";
        }
    }
}
