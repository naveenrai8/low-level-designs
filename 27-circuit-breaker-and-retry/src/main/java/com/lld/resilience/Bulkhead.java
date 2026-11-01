package com.lld.resilience;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A cap on how many calls to one dependency run at the same time.
 *
 * <p>The name comes from the watertight walls in a ship: if one dependency gets slow and its calls pile up, they can fill only their own compartment (this cap) and not eat every thread of the service. A call that finds the compartment full is rejected at once and never runs. See the semaphore guide in the Java concurrency POC for the primitive.
 */
public final class Bulkhead {

    private final String name;
    private final int max;
    private final Semaphore slots;
    private final AtomicLong rejected = new AtomicLong();

    /**
     * Creates a bulkhead.
     *
     * @param name a label for messages
     * @param max the most calls allowed to run at once, at least 1
     */
    public Bulkhead(String name, int max) {
        if (max < 1) {
            throw new IllegalArgumentException("max must be at least 1");
        }
        this.name = Objects.requireNonNull(name, "name");
        this.max = max;
        this.slots = new Semaphore(max);
    }

    /**
     * Runs the call if a slot is free.
     *
     * <p>The slot is given back in a {@code finally}, so it is freed even when the call throws an {@link Error}.
     *
     * @param call the dependency call
     * @param <T> the result type
     * @return whatever the call returned
     * @throws BulkheadFullException if no slot was free; the call was not run
     * @throws Exception whatever the call threw
     */
    public <T> T call(ThrowingSupplier<T> call) throws Exception {
        if (!slots.tryAcquire()) {
            rejected.incrementAndGet();
            throw new BulkheadFullException(name, max);
        }
        try {
            return call.get();
        } finally {
            slots.release();
        }
    }

    /** Returns how many calls are running right now. */
    public int inFlight() {
        return max - slots.availablePermits();
    }

    /** Returns the cap. */
    public int max() {
        return max;
    }

    /** Returns how many calls have been turned away so far. */
    public long rejected() {
        return rejected.get();
    }
}
