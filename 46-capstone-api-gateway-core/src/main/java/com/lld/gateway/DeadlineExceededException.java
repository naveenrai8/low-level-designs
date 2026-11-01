package com.lld.gateway;

/** The request's overall deadline passed (or would pass before the next retry could finish). Not retryable, and not a breaker failure: the backend did nothing wrong. Becomes 504. */
public final class DeadlineExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception. */
    public DeadlineExceededException() {
        super("request deadline exceeded");
    }
}
