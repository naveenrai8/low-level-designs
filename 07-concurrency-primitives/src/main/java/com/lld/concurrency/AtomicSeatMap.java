package com.lld.concurrency;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FIX: one atomic operation that checks and acts. {@code putIfAbsent} returns the previous value,
 * so exactly one caller sees {@code null} and wins. No lock, no window.
 *
 * <p>Same idea elsewhere: {@code AtomicReference.compareAndSet(null, user)} per seat object, or
 * {@code compute} when the act is more than one write (see Topic 21's rate limiter).
 */
public final class AtomicSeatMap implements SeatMap {

    private final ConcurrentHashMap<Integer, String> holders = new ConcurrentHashMap<>();

    @Override
    public boolean book(int seat, String user) {
        return holders.putIfAbsent(seat, user) == null;
    }

    @Override
    public Optional<String> holderOf(int seat) {
        return Optional.ofNullable(holders.get(seat));
    }
}
