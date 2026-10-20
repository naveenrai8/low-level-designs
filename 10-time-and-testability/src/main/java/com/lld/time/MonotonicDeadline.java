package com.lld.time;

import java.time.Duration;

/**
 * <b>Fix.</b> Measures a timeout with a monotonic {@link Ticker}, so wall-clock steps can't
 * stretch or shrink it. The subtraction {@code now - start} is overflow-safe even if
 * {@code nanoTime} wraps, which is why the JDK docs tell you to compare differences, never raw
 * values.
 */
public final class MonotonicDeadline implements Deadline {

    private final Ticker ticker;
    private final long startNanos;
    private final long timeoutNanos;

    public MonotonicDeadline(Ticker ticker, Duration timeout) {
        this.ticker = ticker;
        this.startNanos = ticker.read();
        this.timeoutNanos = timeout.toNanos();
    }

    @Override
    public boolean hasExpired() {
        return ticker.read() - startNanos >= timeoutNanos;
    }

    @Override
    public Duration remaining() {
        long left = timeoutNanos - (ticker.read() - startNanos);
        return Duration.ofNanos(Math.max(0, left));
    }
}
