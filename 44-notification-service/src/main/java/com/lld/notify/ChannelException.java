package com.lld.notify;

/** A failed delivery. The one flag that matters is whether trying again can help: that decides between "retry later" and "straight to the dead letter queue". */
public final class ChannelException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean transientFailure;

    /** Creates the exception; use {@link #transientFailure} or {@link #permanentFailure} so the intent reads clearly. */
    private ChannelException(String message, boolean transientFailure) {
        super(message);
        this.transientFailure = transientFailure;
    }

    /** A failure that may go away by itself (timeout, provider overloaded). It will be retried with backoff. */
    public static ChannelException transientFailure(String message) {
        return new ChannelException(message, true);
    }

    /** A failure that will never go away (invalid address, blocked number). It goes straight to the dead letter queue. */
    public static ChannelException permanentFailure(String message) {
        return new ChannelException(message, false);
    }

    /** Returns true if a retry may succeed. */
    public boolean isTransient() {
        return transientFailure;
    }
}
