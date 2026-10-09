package com.yourcompany.surveyai.call.application.service.impl;

import com.yourcompany.surveyai.call.application.provider.CallProviderRegistry;
import com.yourcompany.surveyai.call.application.provider.ProviderWebhookEvent;
import com.yourcompany.surveyai.call.application.provider.ProviderWebhookRequest;
import com.yourcompany.surveyai.call.application.provider.VoiceExecutionProvider;
import com.yourcompany.surveyai.call.application.service.ProviderExecutionObservationService;
import com.yourcompany.surveyai.call.application.service.ProviderWebhookIngestionService;
import com.yourcompany.surveyai.call.application.service.CallJobDispatcher;
import com.yourcompany.surveyai.call.application.support.CallRetryPolicy;
import com.yourcompany.surveyai.call.application.support.CallStatusSnapshotApplier;
import com.yourcompany.surveyai.call.configuration.VoiceProviderConfiguration;
import com.yourcompany.surveyai.call.configuration.VoiceProviderConfigurationResolver;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import com.yourcompany.surveyai.call.domain.enums.ProviderExecutionOutcome;
import com.yourcompany.surveyai.call.repository.CallAttemptRepository;
import com.yourcompany.surveyai.call.repository.CallJobRepository;
import com.yourcompany.surveyai.common.exception.NotFoundException;
import com.yourcompany.surveyai.common.exception.ValidationException;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.repository.OperationContactRepository;
import com.yourcompany.surveyai.response.application.service.SurveyResponseIngestionService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.EnumSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderWebhookIngestionServiceImpl implements ProviderWebhookIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ProviderWebhookIngestionServiceImpl.class);
    private static final EnumSet<CallAttemptStatus> NON_RESPONSE_TERMINAL_ATTEMPTS = EnumSet.of(
            CallAttemptStatus.NO_ANSWER,
            CallAttemptStatus.BUSY,
            CallAttemptStatus.VOICEMAIL,
            CallAttemptStatus.CANCELLED
    );

    private final CallProviderRegistry callProviderRegistry;
    private final VoiceProviderConfigurationResolver configurationResolver;
    private final CallAttemptRepository callAttemptRepository;
    private final CallJobRepository callJobRepository;
    private final OperationContactRepository operationContactRepository;
    private final SurveyResponseIngestionService surveyResponseIngestionService;
    private final ProviderExecutionObservationService providerExecutionObservationService;
    private static final EnumSet<CallJobStatus> ACTIVE_JOB_STATUSES = EnumSet.of(
            CallJobStatus.QUEUED,
            CallJobStatus.IN_PROGRESS
    );

    private final CallJobDispatcher callJobDispatcher;
    private final CallRetryPolicy callRetryPolicy;

    public ProviderWebhookIngestionServiceImpl(
            CallProviderRegistry callProviderRegistry,
            VoiceProviderConfigurationResolver configurationResolver,
            CallAttemptRepository callAttemptRepository,
            CallJobRepository callJobRepository,
            OperationContactRepository operationContactRepository,
            SurveyResponseIngestionService surveyResponseIngestionService,
            ProviderExecutionObservationService providerExecutionObservationService,
            CallJobDispatcher callJobDispatcher,
            CallRetryPolicy callRetryPolicy
    ) {
        this.callProviderRegistry = callProviderRegistry;
        this.configurationResolver = configurationResolver;
        this.callAttemptRepository = callAttemptRepository;
        this.callJobRepository = callJobRepository;
        this.operationContactRepository = operationContactRepository;
        this.surveyResponseIngestionService = surveyResponseIngestionService;
        this.providerExecutionObservationService = providerExecutionObservationService;
        this.callJobDispatcher = callJobDispatcher;
        this.callRetryPolicy = callRetryPolicy;
    }

    @Override
    @Transactional
    public int ingest(CallProvider providerKey, String rawPayload, HttpServletRequest request) {
        OffsetDateTime receivedAt = OffsetDateTime.now();
        VoiceProviderConfiguration configuration = configurationResolver.getConfiguration(providerKey);
        if (!configuration.enabled()) {
            log.warn("Provider webhook rejected because the provider is disabled. provider={} receivedAt={}", providerKey, receivedAt);
            throw new NotFoundException("Provider webhooks are not enabled for: " + providerKey);
        }
        VoiceExecutionProvider provider = callProviderRegistry.getRequiredProvider(providerKey);
        ProviderWebhookRequest webhookRequest = new ProviderWebhookRequest(providerKey, rawPayload, extractHeaders(request));
        providerExecutionObservationService.recordWebhookReceived(providerKey, rawPayload, receivedAt);

        log.info("Provider webhook received. provider={} receivedAt={} contentLength={}", providerKey, receivedAt, rawPayload == null ? 0 : rawPayload.length());

        if (!provider.verifyWebhook(webhookRequest, configuration)) {
            providerExecutionObservationService.recordWebhookRejected(providerKey, rawPayload, "Provider webhook verification failed", receivedAt);
            log.warn("Provider webhook rejected. provider={} receivedAt={} reason={}", providerKey, receivedAt, "Provider webhook verification failed");
            throw new ValidationException("Provider webhook verification failed");
        }

        List<ProviderWebhookEvent> events = provider.parseWebhook(webhookRequest, configuration);
        int applied = 0;
        for (ProviderWebhookEvent event : events) {
            applied += applyEvent(event);
        }
        return applied;
    }

    private int applyEvent(ProviderWebhookEvent event) {
        Optional<CallAttempt> attemptOptional = resolveAttempt(event);
        // Idempotency-key and correlation lookups are provider-agnostic, so a webhook must never
        // change an attempt that was placed through a different provider.
        if (attemptOptional.isPresent() && attemptOptional.get().getProvider() != event.provider()) {
            log.warn(
                    "Provider webhook ignored because it targets an attempt of another provider. provider={} attemptProvider={} callAttemptId={}",
                    event.provider(),
                    attemptOptional.get().getProvider(),
                    attemptOptional.get().getId()
            );
            attemptOptional = Optional.empty();
        }
        if (attemptOptional.isEmpty()) {
            log.warn(
                    "Provider webhook could not be mapped to a call attempt. provider={} eventType={} providerCallId={} idempotencyKey={}",
                    event.provider(),
                    event.eventType(),
                    event.providerCallId(),
                    event.idempotencyKey()
            );
            recordUnmatchedWebhook(event);
            return 0;
        }

        CallAttempt attempt = attemptOptional.get();
        CallJob callJob = attempt.getCallJob();
        OperationContact contact = attempt.getOperationContact();

        if (isStaleOrDuplicateEvent(attempt, event)) {
            log.info(
                    "Provider webhook skipped as stale/duplicate. provider={} eventType={} callJobId={} callAttemptId={} providerCallId={} currentStatus={} incomingStatus={}",
                    event.provider(),
                    event.eventType(),
                    callJob.getId(),
                    attempt.getId(),
                    firstNonBlank(event.providerCallId(), attempt.getProviderCallId()),
                    attempt.getStatus(),
                    event.attemptStatus()
            );
            providerExecutionObservationService.recordWebhookOutcome(attempt, event, ProviderExecutionOutcome.IGNORED, "Duplicate or stale webhook ignored");
            return 0;
        }

        attempt.setStatus(event.attemptStatus() != null ? event.attemptStatus() : attempt.getStatus());
        attempt.setProviderCallId(event.providerCallId() != null ? event.providerCallId() : attempt.getProviderCallId());
        if (event.occurredAt() != null && attempt.getConnectedAt() == null && event.attemptStatus() == com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus.IN_PROGRESS) {
            attempt.setConnectedAt(event.occurredAt());
        }
        if (isTerminal(event.jobStatus())) {
            attempt.setEndedAt(event.occurredAt() != null ? event.occurredAt() : OffsetDateTime.now());
        }
        if (event.durationSeconds() != null) {
            attempt.setDurationSeconds(event.durationSeconds());
        }
        if (event.errorMessage() != null && !event.errorMessage().isBlank()) {
            attempt.setFailureReason(event.errorMessage());
            if (isTerminal(event.jobStatus()) && (attempt.getHangupReason() == null || attempt.getHangupReason().isBlank())) {
                attempt.setHangupReason(event.errorMessage());
            }
        }
        if (event.transcriptStorageKey() != null && !event.transcriptStorageKey().isBlank()) {
            attempt.setTranscriptStorageKey(event.transcriptStorageKey());
        } else if (event.transcriptText() != null && !event.transcriptText().isBlank() && attempt.getProviderCallId() != null) {
            attempt.setTranscriptStorageKey("inline://elevenlabs/conversations/" + attempt.getProviderCallId());
        }
        attempt.setRawProviderPayload(event.rawPayload() != null ? event.rawPayload() : attempt.getRawProviderPayload());
        callAttemptRepository.save(attempt);

        // Once a job has been retried, a late webhook for an earlier attempt only updates that attempt.
        boolean currentAttempt = CallStatusSnapshotApplier.isCurrentAttempt(callJob, attempt);
        if (currentAttempt) {
            callRetryPolicy.applyReportedStatus(callJob, contact, event.jobStatus(), attempt.getStatus(), OffsetDateTime.now());
            callJob.setLastErrorCode(event.errorCode());
            callJob.setLastErrorMessage(event.errorMessage());
            callJobRepository.save(callJob);

            if (event.occurredAt() != null) {
                contact.setLastCallAt(event.occurredAt());
            }
            operationContactRepository.save(contact);
        }

        if (shouldIngestSurveyResult(event)) {
            surveyResponseIngestionService.ingest(attempt, event);
        }
        if (currentAttempt && !ACTIVE_JOB_STATUSES.contains(callJob.getStatus())) {
            callJobDispatcher.requestDispatch(callJob.getOperation().getId());
        }
        providerExecutionObservationService.recordWebhookOutcome(attempt, event, ProviderExecutionOutcome.ACCEPTED, "Webhook applied to internal state");

        log.info(
                "Provider webhook applied. provider={} eventType={} operationId={} callJobId={} callAttemptId={} providerCallId={} jobStatus={} attemptStatus={}",
                event.provider(),
                event.eventType(),
                callJob.getOperation().getId(),
                callJob.getId(),
                attempt.getId(),
                attempt.getProviderCallId(),
                callJob.getStatus(),
                attempt.getStatus()
        );
        return 1;
    }

    private Optional<CallAttempt> resolveAttempt(ProviderWebhookEvent event) {
        if (event.providerCallId() != null && !event.providerCallId().isBlank()) {
            Optional<CallAttempt> byProviderCallId = callAttemptRepository
                    .findByProviderAndProviderCallIdAndDeletedAtIsNull(event.provider(), event.providerCallId());
            if (byProviderCallId.isPresent()) {
                return byProviderCallId;
            }
        }

        UUID callAttemptId = event.correlationMetadata() != null ? event.correlationMetadata().callAttemptId() : null;
        if (callAttemptId != null) {
            Optional<CallAttempt> byCallAttemptId = callAttemptRepository.findByIdAndDeletedAtIsNull(callAttemptId);
            if (byCallAttemptId.isPresent()) {
                return byCallAttemptId;
            }
        }

        UUID callJobId = event.correlationMetadata() != null ? event.correlationMetadata().callJobId() : null;
        if (callJobId != null) {
            Optional<CallAttempt> byCallJobId = callAttemptRepository.findTopByCallJob_IdAndDeletedAtIsNullOrderByAttemptNumberDesc(callJobId);
            if (byCallJobId.isPresent()) {
                return byCallJobId;
            }
        }

        if (event.idempotencyKey() != null && !event.idempotencyKey().isBlank()) {
            return callJobRepository.findByIdempotencyKeyAndDeletedAtIsNull(event.idempotencyKey())
                    .flatMap(callJob -> callAttemptRepository.findTopByCallJob_IdAndDeletedAtIsNullOrderByAttemptNumberDesc(callJob.getId()));
        }

        return Optional.empty();
    }

    private Map<String, List<String>> extractHeaders(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames == null) {
            return headers;
        }
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            headers.put(headerName, Collections.list(request.getHeaders(headerName)));
        }
        return headers;
    }

    private boolean isTerminal(CallJobStatus status) {
        return status == CallJobStatus.COMPLETED
                || status == CallJobStatus.FAILED
                || status == CallJobStatus.DEAD_LETTER
                || status == CallJobStatus.CANCELLED;
    }

    private boolean shouldIngestSurveyResult(ProviderWebhookEvent event) {
        if (event.attemptStatus() != null && NON_RESPONSE_TERMINAL_ATTEMPTS.contains(event.attemptStatus())) {
            return false;
        }
        return isTerminal(event.jobStatus())
                || (event.transcriptText() != null && !event.transcriptText().isBlank());
    }

    private void recordUnmatchedWebhook(ProviderWebhookEvent event) {
        providerExecutionObservationService.recordWebhookOutcome(null, event, ProviderExecutionOutcome.UNMATCHED, "Webhook did not match an internal call attempt");
    }

    private boolean isStaleOrDuplicateEvent(CallAttempt attempt, ProviderWebhookEvent event) {
        if (event.jobStatus() == null || event.attemptStatus() == null) {
            return false;
        }

        // Compared on the attempt, not the job: after a retry the job has moved on (RETRY or a newer
        // attempt) while this attempt's own webhook is still new.
        int currentRank = statusRank(attempt.getStatus());
        int incomingRank = statusRank(event.attemptStatus());
        if (incomingRank < currentRank) {
            return true;
        }

        if (incomingRank == currentRank && isOlderThanRecordedAttempt(attempt, event)) {
            return true;
        }

        return incomingRank == currentRank
                && sameText(attempt.getProviderCallId(), event.providerCallId())
                && sameText(attempt.getRawProviderPayload(), event.rawPayload())
                && sameText(attempt.getTranscriptStorageKey(), event.transcriptStorageKey())
                && sameText(attempt.getFailureReason(), event.errorMessage())
                && sameInteger(attempt.getDurationSeconds(), event.durationSeconds());
    }

    private boolean isOlderThanRecordedAttempt(CallAttempt attempt, ProviderWebhookEvent event) {
        if (event.occurredAt() == null) {
            return false;
        }

        OffsetDateTime currentTimestamp = firstNonNull(attempt.getEndedAt(), attempt.getConnectedAt(), attempt.getDialedAt());
        return currentTimestamp != null && event.occurredAt().isBefore(currentTimestamp);
    }

    private int statusRank(CallAttemptStatus status) {
        if (status == null) {
            return -1;
        }
        return switch (status) {
            case INITIATED -> 0;
            case RINGING -> 1;
            case IN_PROGRESS -> 2;
            case COMPLETED, FAILED, NO_ANSWER, BUSY, VOICEMAIL, CANCELLED -> 4;
        };
    }

    private boolean sameText(String left, String right) {
        String normalizedLeft = normalizeText(left);
        String normalizedRight = normalizeText(right);
        return normalizedLeft == null ? normalizedRight == null : normalizedLeft.equals(normalizedRight);
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean sameInteger(Integer left, Integer right) {
        return left == null ? right == null : left.equals(right);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
