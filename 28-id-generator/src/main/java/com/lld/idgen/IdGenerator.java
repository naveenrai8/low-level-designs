package com.lld.idgen;

/**
 * Hands out unique 64-bit numeric IDs that grow over time.
 *
 * <p>This is the whiteboard interface. Callers only see "give me the next ID", and how the bits are packed stays hidden behind it.
 *
 * <p>Contract: implementations are thread-safe, and the IDs one instance returns are strictly increasing (never equal, never smaller than an earlier one).
 */
public interface IdGenerator {

    /**
     * Returns the next ID, bigger than every ID this generator returned before.
     *
     * @throws ClockMovedBackwardsException if the wall clock went back further than the generator tolerates
     * @throws IllegalStateException if the clock is before the epoch or the timestamp bits have run out
     */
    long nextId();
}
