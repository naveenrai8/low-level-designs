package com.lld.time;

import java.time.Duration;

/**
 * <b>Fix.</b> Measures a timeout with a monotonic {@link Ticker}, so wall-clock steps cannot stretch or shrink it.
 * <p>The subtraction {@code now - start} stays correct even if {@code nanoTime} wraps around, which is why the JDK docs tell you to compare differences, never raw values.
 */
public final class MonotonicDeadline implements Deadline {

    private final Ticker ticker;
    private final long startNanos;
    private final long timeoutNanos;

    /**
     * Starts the timeout now: remembers the ticker's current reading and the timeout length.
     */
    public MonotonicDeadline(Ticker ticker, Duration timeout) {
        this.ticker = ticker;
        this.startNanos = ticker.read();
        this.timeoutNanos = timeout.toNanos();
    }

    /**
     * True once at least the timeout has elapsed on the ticker. Compares a difference, so it is overflow-safe.
     */
    @Override
    public boolean hasExpired() {
        return ticker.read() - startNanos >= timeoutNanos;
    }

    /**
     * Returns the time left, never below zero.
     */
    @Override
    public Duration remaining() {
        long left = timeoutNanos - (ticker.read() - startNanos);
        return Duration.ofNanos(Math.max(0, left));
    }
}
