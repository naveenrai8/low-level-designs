package com.lld.popularity;

/** A hand-moved clock for tests: time only changes when the test says so, so nothing ever sleeps. */
final class FakeTicker implements Ticker {

    private long now;

    /** Creates a clock at reading zero. */
    FakeTicker() {
        this(0);
    }

    /** Creates a clock at the given starting reading in nanoseconds; it may be negative, as a real nanoTime can be. */
    FakeTicker(long startNanos) {
        this.now = startNanos;
    }

    /** Returns the current fake reading in nanoseconds. */
    @Override
    public long read() {
        return now;
    }

    /** Moves the clock to an absolute reading in seconds. */
    void setSeconds(long s) {
        now = s * 1_000_000_000L;
    }

    /** Moves the clock forward by some seconds. */
    void advanceSeconds(long s) {
        now += s * 1_000_000_000L;
    }

    /** Converts seconds to nanoseconds. */
    static long sec(long s) {
        return s * 1_000_000_000L;
    }
}
