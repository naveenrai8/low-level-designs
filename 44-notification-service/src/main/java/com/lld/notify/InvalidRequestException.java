package com.lld.notify;

/** The request itself is wrong (blank key, unknown user or template, channel not set up for the user, missing template variable). The caller must change it; retrying the same request never helps. The REST layer maps it to 400. */
public final class InvalidRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a message that says what is wrong. */
    public InvalidRequestException(String message) {
        super(message);
    }
}
