package com.lld.connpool;

/** Thrown by a {@link ConnectionFactory} when a new connection cannot be opened. */
public final class ConnectionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a message that says why the open failed. */
    public ConnectionException(String message) {
        super(message);
    }
}
