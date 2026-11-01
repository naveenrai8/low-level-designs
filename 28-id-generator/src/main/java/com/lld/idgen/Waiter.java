package com.lld.idgen;

/**
 * The "wait a moment for the clock to move" seam.
 *
 * <p>When a generator runs out of sequence numbers inside one millisecond it must wait for the next millisecond. It does this by calling {@link #pause()} in a loop and re-reading the clock, so tests can plug in a waiter that advances a fake clock instead of really waiting.
 */
@FunctionalInterface
public interface Waiter {

    /** Gives the clock a chance to move. It may return at once; the caller re-checks the clock and calls again if needed. */
    void pause();

    /** Returns a waiter that busy-spins politely ({@link Thread#onSpinWait()}). The wait is under 1 ms, so sleeping would cost more than it saves. */
    static Waiter spin() {
        return Thread::onSpinWait;
    }
}
