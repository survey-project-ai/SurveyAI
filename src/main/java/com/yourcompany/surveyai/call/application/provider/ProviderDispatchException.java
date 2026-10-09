package com.yourcompany.surveyai.call.application.provider;

// Thrown when a provider rejects or fails to place an outbound call. Retryable failures are those where
// the call certainly was not placed (rate limit, provider outage, connection refused), so another
// attempt cannot ring the respondent twice.
public class ProviderDispatchException extends RuntimeException {

    private final boolean retryable;

    public ProviderDispatchException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public ProviderDispatchException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
