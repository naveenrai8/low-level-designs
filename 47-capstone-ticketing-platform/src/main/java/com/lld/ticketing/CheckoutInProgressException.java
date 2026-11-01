package com.lld.ticketing;

/** Thrown when a second call arrives with a checkout key whose first call is still running. Over HTTP this is a 409 Conflict (IETF Idempotency-Key draft): the caller should wait and retry, and nothing was changed. */
public final class CheckoutInProgressException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Names the key that is busy. */
    public CheckoutInProgressException(String key) {
        super("checkout is already running for key: " + key);
    }
}
