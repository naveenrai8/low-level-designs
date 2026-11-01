package com.lld.connpool;

/** Thrown by {@code borrow} when no connection became free before the timeout ran out. The pool is unchanged by the failed call. */
public final class PoolTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a message that says how long the caller waited. */
    public PoolTimeoutException(String message) {
        super(message);
    }
}
