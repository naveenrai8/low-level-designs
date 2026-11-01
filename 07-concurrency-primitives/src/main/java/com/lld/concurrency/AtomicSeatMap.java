package com.lld.concurrency;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FIX: check and act in one atomic step.
 * <p>{@code putIfAbsent} returns the previous value, so exactly one caller sees {@code null} and wins. There is no lock and no gap between the check and the write.
 * <p>The same idea works elsewhere: {@code AtomicReference.compareAndSet(null, user)} per seat object, or {@code compute} when the act is more than one write (see Topic 21's rate limiter).
 */
public final class AtomicSeatMap implements SeatMap {

    private final ConcurrentHashMap<Integer, String> holders = new ConcurrentHashMap<>();

    /**
     * Books the seat if nobody holds it. Only the first caller gets true, because {@code putIfAbsent} is atomic.
     */
    @Override
    public boolean book(int seat, String user) {
        return holders.putIfAbsent(seat, user) == null;
    }

    /**
     * Looks up who holds the seat, or empty if it is free.
     */
    @Override
    public Optional<String> holderOf(int seat) {
        return Optional.ofNullable(holders.get(seat));
    }
}
