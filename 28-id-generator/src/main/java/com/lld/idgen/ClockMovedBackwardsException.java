package com.lld.idgen;

/** Thrown when the wall clock is behind the last issued timestamp by more than the generator's tolerance. Issuing an ID anyway could repeat an old one, so the generator refuses. */
public final class ClockMovedBackwardsException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final long driftMillis;

    /** Creates the exception for a clock that is {@code driftMillis} behind the last issued timestamp. */
    public ClockMovedBackwardsException(long driftMillis, long toleranceMillis) {
        super("clock moved backwards by " + driftMillis + " ms (tolerance " + toleranceMillis + " ms)");
        this.driftMillis = driftMillis;
    }

    /** Returns how many milliseconds the clock is behind the last issued timestamp. */
    public long driftMillis() {
        return driftMillis;
    }
}
