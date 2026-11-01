package com.lld.elevator;

import java.util.concurrent.atomic.AtomicLong;

/** A {@link Ticker} that moves only when someone calls {@link #advance()}. It is the "virtual time" of the simulation: no sleeping, no wall clock. */
public final class VirtualTicker implements Ticker {

    private final AtomicLong now = new AtomicLong();

    /** Returns the current virtual tick, starting at 0. */
    @Override
    public long read() {
        return now.get();
    }

    /** Moves virtual time forward by one tick and returns the new tick. */
    public long advance() {
        return now.incrementAndGet();
    }
}
