package com.yourcompany.surveyai.call.application.support;

import com.yourcompany.surveyai.call.application.provider.ProviderCallStatusResult;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.repository.CallAttemptRepository;
import com.yourcompany.surveyai.call.repository.CallJobRepository;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.repository.OperationContactRepository;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;

// Applies a provider status snapshot (polled after dispatch or by reconciliation) to a call attempt,
// and to its job and contact while the attempt is still the job's current one. Must run inside a transaction.
@Component
public class CallStatusSnapshotApplier {

    private static final Set<CallAttemptStatus> FINAL_ATTEMPT_STATUSES = EnumSet.of(
            CallAttemptStatus.COMPLETED,
            CallAttemptStatus.FAILED,
            CallAttemptStatus.NO_ANSWER,
            CallAttemptStatus.BUSY,
            CallAttemptStatus.VOICEMAIL,
            CallAttemptStatus.CANCELLED
    );

    private final CallJobRepository callJobRepository;
    private final CallAttemptRepository callAttemptRepository;
    private final OperationContactRepository operationContactRepository;
    private final CallRetryPolicy callRetryPolicy;

    public CallStatusSnapshotApplier(
            CallJobRepository callJobRepository,
            CallAttemptRepository callAttemptRepository,
            OperationContactRepository operationContactRepository,
            CallRetryPolicy callRetryPolicy
    ) {
        this.callJobRepository = callJobRepository;
        this.callAttemptRepository = callAttemptRepository;
        this.operationContactRepository = operationContactRepository;
        this.callRetryPolicy = callRetryPolicy;
    }

    // Returns true when the snapshot finished the attempt.
    public boolean apply(CallAttempt attempt, ProviderCallStatusResult snapshot) {
        if (snapshot == null || snapshot.jobStatus() == null) {
            return false;
        }
        // A webhook may already have finished this attempt; a later snapshot must not reopen it.
        if (isFinal(attempt.getStatus())) {
            return false;
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (snapshot.attemptStatus() != null) {
            attempt.setStatus(snapshot.attemptStatus());
        }
        if (snapshot.rawPayload() != null) {
            attempt.setRawProviderPayload(snapshot.rawPayload());
        }
        if (snapshot.occurredAt() != null && attempt.getConnectedAt() == null
                && snapshot.attemptStatus() == CallAttemptStatus.IN_PROGRESS) {
            attempt.setConnectedAt(snapshot.occurredAt());
        }
        boolean finished = CallRetryPolicy.isTerminal(snapshot.jobStatus());
        if (finished) {
            attempt.setEndedAt(snapshot.occurredAt() != null ? snapshot.occurredAt() : now);
        }
        callAttemptRepository.save(attempt);

        CallJob job = attempt.getCallJob();
        if (isCurrentAttempt(job, attempt)) {
            OperationContact contact = attempt.getOperationContact();
            callRetryPolicy.applyReportedStatus(job, contact, snapshot.jobStatus(), attempt.getStatus(), now);
            if (snapshot.occurredAt() != null) {
                contact.setLastCallAt(snapshot.occurredAt());
            }
            callJobRepository.save(job);
            operationContactRepository.save(contact);
        }
        return finished;
    }

    // Older attempts of a job that has already been retried must not change the job any more.
    public static boolean isCurrentAttempt(CallJob job, CallAttempt attempt) {
        if (attempt.getAttemptNumber() == null || job.getAttemptCount() == null) {
            return true;
        }
        return attempt.getAttemptNumber() >= job.getAttemptCount();
    }

    public static boolean isFinal(CallAttemptStatus status) {
        return status != null && FINAL_ATTEMPT_STATUSES.contains(status);
    }
}
