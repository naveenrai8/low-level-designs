package com.lld.resilience;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * Tries a call again after a failure, waiting longer each time, with a budget and random jitter.
 *
 * <p>Rules: (1) only an exception the {@code retryable} test accepts is retried; (2) {@link CircuitOpenException}, {@link BulkheadFullException} and {@link InterruptedException} are never retried (the first two would get the same answer, the third is the caller saying stop, so the interrupt flag is restored), because asking again at once will get the same answer and only adds load; (3) a retry must be paid for from the {@link RetryBudget}; (4) the wait is exponential backoff with "equal jitter" (half fixed, half random) as in Topic 23; (5) the wait goes through the injected {@link Sleeper}.
 *
 * <p>Put the retry on the outside and the breaker inside, so every attempt asks the breaker and an OPEN breaker ends the loop at once.
 */
public final class Retry {

    private final int maxAttempts;
    private final long baseMillis;
    private final long capMillis;
    private final RandomGenerator random;
    private final Sleeper sleeper;
    private final RetryBudget budget;
    private final Predicate<Throwable> retryable;

    /**
     * Creates a retry helper.
     *
     * @param maxAttempts total tries including the first, at least 1
     * @param base the backoff before the second try (before jitter), at least 1 ms
     * @param cap the largest backoff (before jitter), at least base
     * @param random the jitter source; pass a seeded one in tests
     * @param sleeper how to wait; pass a recorder in tests
     * @param budget the retry limit, or {@link RetryBudget#unlimited()}
     * @param retryable decides which exceptions are worth another try
     */
    public Retry(int maxAttempts, Duration base, Duration cap, RandomGenerator random, Sleeper sleeper, RetryBudget budget, Predicate<Throwable> retryable) {
        if (maxAttempts < 1 || base.toMillis() < 1 || cap.compareTo(base) < 0) {
            throw new IllegalArgumentException("need maxAttempts >= 1 and 1 ms <= base <= cap");
        }
        this.maxAttempts = maxAttempts;
        this.baseMillis = base.toMillis();
        this.capMillis = cap.toMillis();
        this.random = Objects.requireNonNull(random, "random");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.retryable = Objects.requireNonNull(retryable, "retryable");
    }

    /**
     * Runs the call, retrying on retryable failures.
     *
     * <p>The budget is told about the request once, here, not once per attempt.
     *
     * @param call the call to run
     * @param <T> the result type
     * @return the first successful result
     * @throws Exception the last failure when attempts or budget run out, or at once for a non-retryable one; if the sleep is interrupted, the {@link InterruptedException} with the interrupt flag restored
     */
    public <T> T call(ThrowingSupplier<T> call) throws Exception {
        budget.onRequest();
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (CircuitOpenException | BulkheadFullException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                if (!retryable.test(e) || attempt >= maxAttempts || !budget.tryRetry()) {
                    throw e;
                }
                try {
                    sleeper.sleep(delayAfter(attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
        }
    }

    /**
     * Returns the wait after the given failed attempt: backoff doubles from base up to cap, then half of it is kept and the other half is random.
     *
     * <p>Each call draws from the random source.
     */
    Duration delayAfter(int failedAttempts) {
        long backoff = baseMillis;
        for (int i = 1; i < failedAttempts && backoff < capMillis; i++) {
            backoff = backoff > capMillis / 2 ? capMillis : backoff * 2;
        }
        long half = backoff / 2;
        return Duration.ofMillis(half + random.nextLong(backoff - half + 1));
    }
}
