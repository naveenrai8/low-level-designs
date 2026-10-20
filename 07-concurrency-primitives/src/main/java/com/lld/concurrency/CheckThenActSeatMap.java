package com.lld.concurrency;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BUG: check-then-act. {@code containsKey} and {@code put} are each atomic; the pair is not.
 * Two threads can both see "free", both put, both return true: a double booking where the
 * second put silently overwrites the first holder.
 *
 * <p>Using a {@link ConcurrentHashMap} does not help — a thread-safe collection makes each call
 * safe, never a sequence of calls.
 */
public final class CheckThenActSeatMap implements SeatMap {

    private final ConcurrentHashMap<Integer, String> holders = new ConcurrentHashMap<>();
    private final RaceWindow window;

    public CheckThenActSeatMap(RaceWindow window) {
        this.window = window;
    }

    @Override
    public boolean book(int seat, String user) {
        if (!holders.containsKey(seat)) {   // check
            window.pause();                 // ...another thread can check here too...
            holders.put(seat, user);        // act
            return true;
        }
        return false;
    }

    @Override
    public Optional<String> holderOf(int seat) {
        return Optional.ofNullable(holders.get(seat));
    }
}
