package com.lld.gateway;

/** The backend did not answer within the timeout it was given. The gateway turns it into 504 and may retry it. */
public final class UpstreamTimeoutException extends UpstreamException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a message for the log. */
    public UpstreamTimeoutException(String message) {
        super(message);
    }
}
