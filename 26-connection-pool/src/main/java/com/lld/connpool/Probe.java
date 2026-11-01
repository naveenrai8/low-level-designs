package com.lld.connpool;

/**
 * Test seams: points inside the pool where a test can pause a thread to force a particular interleaving.
 *
 * <p>Production code uses {@link #NONE}, which does nothing.
 */
public interface Probe {

    /** A probe that does nothing. */
    Probe NONE = new Probe() { };

    /** Called after a borrower reserved a slot for a new connection but before it opens it. Runs without the pool lock. */
    default void afterReserveBeforeCreate() {
    }

    /** Called while holding the pool lock, after "nothing free and no room" was decided and just before the thread starts waiting. */
    default void beforeWait() {
    }

    /** Called while holding the pool lock, right after a waiter wakes up (for any reason), before it re-checks. Lets a test count wake-ups. */
    default void afterWake() {
    }
}
