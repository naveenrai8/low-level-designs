package com.lld.scheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * How long to wait between attempts of a failing job: exponential backoff with a cap and "equal jitter".
 *
 * <p>Exponential backoff means the wait doubles after every failure: base, 2 x base, 4 x base, and so on, never above the cap. Jitter means a random part is mixed in so that many jobs that failed together do not all retry at the same instant. Equal jitter keeps half of the wait fixed and randomises the other half, so the wait is always between half and all of the backoff value.
 *
 * <p>The random source is injected. Pass {@code new Random(seed)} and the delays are exactly repeatable.
 */
public final class RetryPolicy {

    private final int maxAttempts;
    private final long baseMillis;
    private final long capMillis;
    private final RandomGenerator random;

    /**
     * Creates a policy.
     *
     * @param maxAttempts total number of tries including the first one; 1 means "never retry"
     * @param base the backoff before the second attempt (before jitter)
     * @param cap the largest backoff ever used (before jitter)
     * @param random the jitter source; pass a seeded generator in tests
     * @throws IllegalArgumentException if maxAttempts is below 1, base is below 1 ms, or cap is below base
     */
    public RetryPolicy(int maxAttempts, Duration base, Duration cap, RandomGenerator random) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.baseMillis = millis(base);
        this.capMillis = millis(cap);
        if (baseMillis < 1 || capMillis < baseMillis) {
            throw new IllegalArgumentException("need 1 ms <= base <= cap");
        }
        this.maxAttempts = maxAttempts;
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Converts a Duration to milliseconds, clamping a Duration too big for a long to {@code Long.MAX_VALUE} instead of throwing. */
    private static long millis(Duration d) {
        try {
            return d.toMillis();
        } catch (ArithmeticException e) {
            return d.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    /** Returns the total number of tries allowed, the first one included. */
    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Returns how long to wait after the given failed attempt before trying again.
     *
     * <p>Each call draws from the random source, so calling it twice for the same attempt gives two different answers. The scheduler calls it exactly once per failure.
     *
     * @param failedAttempts how many attempts have failed so far, starting at 1
     * @return a wait in whole milliseconds, between half the backoff value and all of it
     */
    public Duration delayAfter(int failedAttempts) {
        long backoff = baseMillis;
        for (int i = 1; i < failedAttempts && backoff < capMillis; i++) {
            backoff = backoff > capMillis / 2 ? capMillis : backoff * 2;
        }
        long half = backoff / 2;
        return Duration.ofMillis(half + random.nextLong(backoff - half + 1));
    }
}
