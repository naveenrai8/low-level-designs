package com.lld.notify;

/** An idempotency key was reused with a different request. Reusing a key for different content is a caller bug, so we refuse instead of guessing which one they meant. The REST layer maps it to 422, as the IETF Idempotency-Key draft (section 2.7) asks. The draft's 409 is for a retry that arrives while the first request is still running, which cannot happen here because submit finishes before it returns. */
public final class IdempotencyConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception naming the key. */
    public IdempotencyConflictException(String key) {
        super("idempotency key already used with a different request: " + key);
    }
}
