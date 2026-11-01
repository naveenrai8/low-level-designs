package com.lld.metrics;

/**
 * A test hook that production code calls at one risky spot (between "pick" and "write", or between a lock-free miss and the locked re-check).
 *
 * <p>Tests use it to park a thread exactly there, so a race is forced instead of hoped for. Production code uses {@link #NONE}.
 */
@FunctionalInterface
interface Gap {

    /** Does nothing. */
    Gap NONE = () -> { };

    /** Runs at the risky spot. */
    void pause();
}
