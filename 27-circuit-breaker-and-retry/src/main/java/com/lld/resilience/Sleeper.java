package com.lld.resilience;

import java.time.Duration;

/**
 * The seam for "wait before the next try".
 *
 * <p>The retry loop never calls {@code Thread.sleep} itself. Production passes {@link #system()}; tests pass a recorder that only remembers the delays, so the exact delay sequence can be asserted without waiting.
 */
@FunctionalInterface
public interface Sleeper {

    /**
     * Waits for the given time.
     *
     * @param delay how long to wait
     * @throws InterruptedException if the thread is interrupted while waiting; the caller must restore the interrupt flag
     */
    void sleep(Duration delay) throws InterruptedException;

    /** Returns a sleeper that really blocks the calling thread, for production use. */
    static Sleeper system() {
        return d -> Thread.sleep(d);
    }
}
