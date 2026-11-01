package com.lld.notify;

import java.time.Duration;
import java.util.Objects;
import java.util.Random;

/**
 * How long to wait before trying again, and when to give up.
 *
 * <p><em>Exponential backoff</em> means each wait is double the last (1s, 2s, 4s, ...) up to a cap, so a struggling provider gets breathing room. <em>Jitter</em> means we shave a random part off each wait, so a thousand messages that failed together do not all retry in the same instant.
 *
 * <p>We use "equal jitter": the wait is half the backoff plus a random amount between zero and the other half. The random source is seeded, so tests get the same numbers every run.
 *
 * <p>Example with base 1s, cap 8s: attempt 1 fails then waits in [0.5s, 1s]; attempt 2 in [1s, 2s]; attempt 3 in [2s, 4s]; attempt 4 in [4s, 8s]; later attempts stay in [4s, 8s].
 */
public final class RetryPolicy {

    private final int maxAttempts;
    private final Duration base;
    private final Duration cap;
    private final Random random;

    /**
     * Creates a policy.
     *
     * @param maxAttempts how many channel calls are allowed in total, first try included (at least 1)
     * @param base the backoff after the first failure
     * @param cap the longest backoff
     * @param random the (seeded) source of jitter; used from one thread at a time
     * @throws IllegalArgumentException if maxAttempts is below 1, base is under 1 millisecond, or cap is under base
     */
    public RetryPolicy(int maxAttempts, Duration base, Duration cap, Random random) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1: " + maxAttempts);
        }
        if (base.toMillis() < 1 || cap.compareTo(base) < 0) {
            // Waits are counted in whole milliseconds, so a base under 1 ms would give a wait of 0.
            throw new IllegalArgumentException("need 1 ms <= base <= cap");
        }
        this.maxAttempts = maxAttempts;
        this.base = base;
        this.cap = cap;
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Returns how many channel calls are allowed in total. */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** Returns the wait after the {@code attempt}-th failed call (1 for the first failure), with jitter. */
    public Duration delayAfter(int attempt) {
        long baseMs = base.toMillis();
        long capMs = cap.toMillis();
        long backoff = capMs;
        // Doubling is only safe while it cannot overflow or pass the cap; after that the cap wins.
        if (attempt - 1 < 32 && baseMs <= (capMs >> (attempt - 1))) {
            backoff = baseMs << (attempt - 1);
        }
        long half = backoff / 2;
        long jitter = half == 0 ? 0 : (long) (random.nextDouble() * (backoff - half + 1));
        // jitter is at most backoff - half, so this never passes the backoff
        return Duration.ofMillis(half + jitter);
    }
}
