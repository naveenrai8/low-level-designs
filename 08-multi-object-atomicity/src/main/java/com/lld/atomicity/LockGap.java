package com.lld.atomicity;

/**
 * Test seam between two steps that ought to be one: between taking the first and second lock, or between the two legs of a transfer.
 * <p>Production passes {@link #NONE}. Tests pass a barrier (to force a deadlock) or an auditor (to observe the half-done state).
 */
@FunctionalInterface
public interface LockGap {

    LockGap NONE = () -> { };

    /**
     * Called in the gap. Does nothing in production, and waits or observes in tests.
     */
    void pause();
}
