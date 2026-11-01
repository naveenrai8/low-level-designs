package com.lld.booking;

/** The idempotency key was already used for a different request (another hold or another payment reference). Replaying the same request is fine, changing it is not. */
public final class IdempotencyConflictException extends BookingException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception for the given id. */
    public IdempotencyConflictException(String id) {
        super("idempotency key reused for a different request: " + id);
    }
}
