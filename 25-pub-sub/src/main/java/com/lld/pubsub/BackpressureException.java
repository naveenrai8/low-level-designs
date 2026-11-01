package com.lld.pubsub;

/** Thrown by publish when some subscriber group is too far behind. The producer should slow down or retry later. */
public final class BackpressureException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message which group is behind and by how much
     */
    public BackpressureException(String message) {
        super(message);
    }
}
