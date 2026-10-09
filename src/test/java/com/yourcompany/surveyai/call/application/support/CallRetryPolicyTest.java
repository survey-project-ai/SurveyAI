package com.yourcompany.surveyai.call.application.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.yourcompany.surveyai.call.configuration.CallQueueProperties;
import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.domain.enums.OperationContactStatus;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class CallRetryPolicyTest {

    private final CallRetryPolicy policy = new CallRetryPolicy(new CallQueueProperties());
    private final OffsetDateTime now = OffsetDateTime.parse("2026-07-06T12:00:00+03:00");

    @Test
    void schedulesAnotherAttemptTwoHoursLaterForUnansweredCall() {
        CallJob job = job(1, 3);
        OperationContact contact = contact();

        policy.applyReportedStatus(job, contact, CallJobStatus.FAILED, CallAttemptStatus.NO_ANSWER, now);

        assertThat(job.getStatus()).isEqualTo(CallJobStatus.RETRY);
        assertThat(job.getAvailableAt()).isEqualTo(now.plusHours(2));
        assertThat(contact.getStatus()).isEqualTo(OperationContactStatus.RETRY);
        assertThat(contact.getNextRetryAt()).isEqualTo(now.plusHours(2));
        assertThat(contact.getRetryCount()).isEqualTo(1);
    }

    @Test
    void failsJobOnceAllAttemptsAreUsed() {
        CallJob job = job(3, 3);
        OperationContact contact = contact();

        policy.applyReportedStatus(job, contact, CallJobStatus.FAILED, CallAttemptStatus.BUSY, now);

        assertThat(job.getStatus()).isEqualTo(CallJobStatus.FAILED);
        assertThat(contact.getStatus()).isEqualTo(OperationContactStatus.FAILED);
        assertThat(contact.getNextRetryAt()).isNull();
    }

    @Test
    void doesNotRetryProviderFailuresOrCompletedCalls() {
        CallJob failedJob = job(1, 3);
        policy.applyReportedStatus(failedJob, contact(), CallJobStatus.FAILED, CallAttemptStatus.FAILED, now);

        CallJob completedJob = job(1, 3);
        OperationContact completedContact = contact();
        policy.applyReportedStatus(completedJob, completedContact, CallJobStatus.COMPLETED, CallAttemptStatus.COMPLETED, now);

        assertThat(failedJob.getStatus()).isEqualTo(CallJobStatus.FAILED);
        assertThat(completedJob.getStatus()).isEqualTo(CallJobStatus.COMPLETED);
        assertThat(completedContact.getStatus()).isEqualTo(OperationContactStatus.COMPLETED);
    }

    @Test
    void retriesDispatchFailureOnlyWhenTheCallWasCertainlyNotPlaced() {
        CallJob retryableJob = job(1, 3);
        policy.applyDispatchFailure(retryableJob, contact(), true, now);

        CallJob permanentJob = job(1, 3);
        policy.applyDispatchFailure(permanentJob, contact(), false, now);

        assertThat(retryableJob.getStatus()).isEqualTo(CallJobStatus.RETRY);
        assertThat(retryableJob.getAvailableAt()).isEqualTo(now.plusMinutes(5));
        assertThat(permanentJob.getStatus()).isEqualTo(CallJobStatus.FAILED);
    }

    private CallJob job(int attemptCount, int maxAttempts) {
        CallJob job = new CallJob();
        job.setStatus(CallJobStatus.QUEUED);
        job.setAttemptCount(attemptCount);
        job.setMaxAttempts(maxAttempts);
        job.setLockedBy("instance-1");
        return job;
    }

    private OperationContact contact() {
        OperationContact contact = new OperationContact();
        contact.setStatus(OperationContactStatus.CALLING);
        contact.setRetryCount(0);
        return contact;
    }
}
