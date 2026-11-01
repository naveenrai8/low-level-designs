package com.lld.ticketing;

/** Thrown when a checkout key is reused with a different request. It is a caller bug: one key means one purchase. */
public final class CheckoutKeyConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Names the key that was reused. */
    public CheckoutKeyConflictException(String key) {
        super("checkout key reused with a different request: " + key);
    }
}
