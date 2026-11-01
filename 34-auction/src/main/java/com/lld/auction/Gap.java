package com.lld.auction;

/**
 * Test seam inside the bid code, so a test can force an interleaving with latches instead of sleeping. Production passes {@link #NONE}.
 */
@FunctionalInterface
public interface Gap {

    /** Does nothing. */
    Gap NONE = phase -> { };

    /** The two moments a test may pause at. */
    enum Phase {
        /** The bid call just started and nothing was read or locked. */
        ENTERED,
        /** The new state is computed from what was read, and is not yet written. */
        AFTER_READ
    }

    /** Called at each phase. Does nothing in production; waits on a latch or records in tests. */
    void at(Phase phase);
}
