package com.lld.editor;

/** A clock that tests move by hand, so no test ever sleeps. */
final class FakeTicker implements Ticker {

    private long nanos;

    /** Creates a clock starting at one second. */
    FakeTicker() {
        this(1_000_000_000L);
    }

    /** Creates a clock starting at {@code start} nanoseconds; tests use a value near {@link Long#MAX_VALUE} to cross the wrap. */
    FakeTicker(long start) {
        nanos = start;
    }

    /** Returns the current fake time in nanoseconds. */
    @Override
    public long read() {
        return nanos;
    }

    /** Moves the fake time forward by {@code millis} milliseconds. */
    void advanceMillis(long millis) {
        nanos += millis * 1_000_000L;
    }
}
