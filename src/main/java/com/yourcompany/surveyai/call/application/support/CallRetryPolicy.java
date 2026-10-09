package com.yourcompany.surveyai.call.application.support;

import com.yourcompany.surveyai.call.configuration.CallQueueProperties;
import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.domain.enums.OperationContactStatus;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;

// Single place that moves a call job and its contact to their next state after a call or a dispatch
// failure. Unanswered calls are tried again later until the job runs out of attempts.
@Component
public class CallRetryPolicy {

    private static final Set<CallAttemptStatus> RETRYABLE_CALL_OUTCOMES = EnumSet.of(
            CallAttemptStatus.NO_ANSWER,
            CallAttemptStatus.BUSY,
            CallAttemptStatus.VOICEMAIL
    );

    private final CallQueueProperties properties;

    public CallRetryPolicy(CallQueueProperties properties) {
        this.properties = properties;
    }

    // Applies a status reported for the job's current attempt.
    public void applyReportedStatus(
            CallJob job,
            OperationContact contact,
            CallJobStatus reportedJobStatus,
            CallAttemptStatus attemptStatus,
            OffsetDateTime now
    ) {
        if (reportedJobStatus == null) {
            return;
        }
        if (reportedJobStatus == CallJobStatus.FAILED
                && attemptStatus != null
                && RETRYABLE_CALL_OUTCOMES.contains(attemptStatus)
                && hasAttemptsLeft(job)) {
            scheduleRetry(job, contact, now.plus(properties.getRetryDelay()));
            return;
        }

        job.setStatus(reportedJobStatus);
        contact.setStatus(mapContactStatus(reportedJobStatus));
        if (isTerminal(reportedJobStatus)) {
            job.setLockedAt(null);
            job.setLockedBy(null);
            contact.setNextRetryAt(null);
        }
    }

    // Applies a failure to place the call at all. Only failures where the call certainly was not placed
    // are retried, so a respondent is never rung twice by an automatic retry.
    public void applyDispatchFailure(CallJob job, OperationContact contact, boolean retryable, OffsetDateTime now) {
        if (retryable && hasAttemptsLeft(job)) {
            scheduleRetry(job, contact, now.plus(properties.getDispatchRetryDelay()));
            return;
        }
        job.setStatus(CallJobStatus.FAILED);
        job.setLockedAt(null);
        job.setLockedBy(null);
        contact.setStatus(OperationContactStatus.FAILED);
        contact.setNextRetryAt(null);
    }

    public static boolean isTerminal(CallJobStatus status) {
        return status == CallJobStatus.COMPLETED
                || status == CallJobStatus.FAILED
                || status == CallJobStatus.DEAD_LETTER
                || status == CallJobStatus.CANCELLED;
    }

    public static OperationContactStatus mapContactStatus(CallJobStatus jobStatus) {
        return switch (jobStatus) {
            case PENDING, QUEUED, IN_PROGRESS -> OperationContactStatus.CALLING;
            case COMPLETED -> OperationContactStatus.COMPLETED;
            case RETRY -> OperationContactStatus.RETRY;
            case FAILED, DEAD_LETTER, CANCELLED -> OperationContactStatus.FAILED;
        };
    }

    private boolean hasAttemptsLeft(CallJob job) {
        int attemptCount = job.getAttemptCount() == null ? 0 : job.getAttemptCount();
        int maxAttempts = job.getMaxAttempts() == null ? 1 : job.getMaxAttempts();
        return attemptCount < maxAttempts;
    }

    private void scheduleRetry(CallJob job, OperationContact contact, OffsetDateTime retryAt) {
        job.setStatus(CallJobStatus.RETRY);
        job.setAvailableAt(retryAt);
        job.setLockedAt(null);
        job.setLockedBy(null);
        contact.setStatus(OperationContactStatus.RETRY);
        contact.setNextRetryAt(retryAt);
        contact.setRetryCount((contact.getRetryCount() == null ? 0 : contact.getRetryCount()) + 1);
    }
}
