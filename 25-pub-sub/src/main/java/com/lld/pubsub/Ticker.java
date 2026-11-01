package com.lld.pubsub;

/**
 * A clock that only moves forward, counted in nanoseconds.
 *
 * <p>Ack deadlines are about how much time has passed, so we use a monotonic clock. Passing it in lets tests move a fake ticker instead of sleeping.
 */
@FunctionalInterface
public interface Ticker {

    /** Returns the current reading in nanoseconds. Only the difference between two readings means anything. */
    long read();

    /** Returns the real clock, {@link System#nanoTime()}, for production use. */
    static Ticker system() {
        return System::nanoTime;
    }
}
