package com.lld.gateway;

/** The backend could not give an answer (connection refused, reset, ...). The gateway turns it into 502 and may retry it. */
public class UpstreamException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a message for the log. */
    public UpstreamException(String message) {
        super(message);
    }
}
