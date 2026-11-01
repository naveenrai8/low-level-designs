package com.lld.concurrency;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BUG: check-then-act. {@code containsKey} and {@code put} are each atomic, but the pair is not.
 * <p>Example: Ann and Bob both ask for seat 7. Both run {@code containsKey} and see "free". Both then {@code put} and both get true. The second put silently overwrites the first holder, so seat 7 is double booked.
 * <p>Using a {@link ConcurrentHashMap} does not help. A thread-safe collection makes each single call safe, never a sequence of calls.
 */
public final class CheckThenActSeatMap implements SeatMap {

    private final ConcurrentHashMap<Integer, String> holders = new ConcurrentHashMap<>();
    private final RaceWindow window;

    /**
     * Creates the broken map. The window lets a test pause every thread between the check and the write.
     */
    public CheckThenActSeatMap(RaceWindow window) {
        this.window = window;
    }

    /**
     * Checks that the seat is free, pauses in the gap, then writes. Two threads can both pass the check, so both can return true.
     */
    @Override
    public boolean book(int seat, String user) {
        if (!holders.containsKey(seat)) {   // check
            window.pause();                 // ...another thread can check here too...
            holders.put(seat, user);        // act
            return true;
        }
        return false;
    }

    /**
     * Looks up who holds the seat, or empty if it is free.
     */
    @Override
    public Optional<String> holderOf(int seat) {
        return Optional.ofNullable(holders.get(seat));
    }
}
