package com.lld.booking;

/**
 * Test seam inside the locking code, so a test can force an interleaving with a barrier instead of sleeping. Production passes {@link #NONE}.
 */
@FunctionalInterface
public interface Gap {

    /** Does nothing. */
    Gap NONE = (phase, seatId) -> { };

    /** The two moments a test may pause at. */
    enum Phase {
        /** Before any seat lock of a call is taken. */
        BEFORE_LOCKING,
        /** Right after the first seat lock of a call is taken, before the second. */
        AFTER_FIRST_LOCK,
        /** In confirm, after the idempotency key was checked and before the seat locks are taken. */
        AFTER_KEY_LOOKUP
    }

    /** Called at each phase. {@code seatId} is the seat just locked for AFTER_FIRST_LOCK and null otherwise. Does nothing in production, and waits at a barrier or records in tests. */
    void at(Phase phase, String seatId);
}
