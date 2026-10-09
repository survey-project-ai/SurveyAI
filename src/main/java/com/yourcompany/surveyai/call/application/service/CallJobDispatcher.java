package com.yourcompany.surveyai.call.application.service;

import java.util.UUID;

public interface CallJobDispatcher {

    // Throws ValidationException when the active provider cannot place calls.
    void validateActiveProviderConfiguration();

    // Dispatches the operation's next eligible jobs on a background thread once the current
    // transaction commits (immediately when there is none). The scheduler also picks the operation
    // up, so a lost request only delays the call.
    void requestDispatch(UUID operationId);

    // Claims and places the operation's next eligible calls now, respecting the calling hours and the
    // per-operation concurrency limit. Must not run inside a transaction. Returns the number of calls attempted.
    int dispatchAvailableJobs(UUID operationId);
}
