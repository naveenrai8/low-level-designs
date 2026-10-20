package com.lld.atomicity;

/**
 * Test seam between two steps that ought to be one: between taking the first and second lock, or
 * between the two legs of a transfer. Production passes {@link #NONE}; tests pass a barrier (to
 * force a deadlock) or an auditor (to observe the half-done state).
 */
@FunctionalInterface
public interface LockGap {

    LockGap NONE = () -> { };

    void pause();
}
