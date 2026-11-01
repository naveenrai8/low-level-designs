package com.lld.blockingqueue;

/**
 * Thrown when someone puts into a closed queue, or takes from a closed queue that has no items left.
 *
 * <p>It is unchecked on purpose: "closed" is a normal end-of-life signal, like the end of a file, not a failure of the caller.
 */
public final class QueueClosedException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a short explanation of which call hit the closed queue. */
    public QueueClosedException(String message) {
        super(message);
    }
}
