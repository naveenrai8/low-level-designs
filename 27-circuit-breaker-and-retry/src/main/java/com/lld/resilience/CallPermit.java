package com.lld.resilience;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A ticket for one admitted call. It remembers the breaker "generation" it was issued in, so a late result can be told apart from a current one.
 *
 * <p>Report exactly one outcome: {@link #success()}, {@link #failure()} or {@link #ignore()}. A second report throws.
 */
public final class CallPermit {

    private final CircuitBreaker breaker;
    private final long generation;
    private final AtomicBoolean reported = new AtomicBoolean();

    /** Creates a permit for the given breaker and generation. Only the breaker calls this. */
    CallPermit(CircuitBreaker breaker, long generation) {
        this.breaker = breaker;
        this.generation = generation;
    }

    /** Returns the generation (state-change counter) in which this call was admitted. */
    public long generation() {
        return generation;
    }

    /** Reports that the call succeeded. If the breaker has changed state since this permit was issued, the result is dropped. */
    public void success() {
        claim();
        breaker.report(generation, Outcome.SUCCESS);
    }

    /** Reports that the call failed. If the breaker has changed state since this permit was issued, the result is dropped. */
    public void failure() {
        claim();
        breaker.report(generation, Outcome.FAILURE);
    }

    /** Reports that the call ended without a verdict about the dependency; it counts for nothing and frees any probe slot it held. */
    public void ignore() {
        claim();
        breaker.report(generation, Outcome.IGNORED);
    }

    /** Marks the permit as used, or throws if it was already used. */
    private void claim() {
        if (!reported.compareAndSet(false, true)) {
            throw new IllegalStateException("this permit already reported an outcome");
        }
    }

    /** What a finished call tells the breaker. */
    enum Outcome {
        /** The dependency answered well. */
        SUCCESS,
        /** The dependency failed. */
        FAILURE,
        /** No verdict. */
        IGNORED
    }
}
