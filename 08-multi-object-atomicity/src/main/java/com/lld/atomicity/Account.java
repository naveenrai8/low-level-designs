package com.lld.atomicity;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Money is {@code long} minor units (cents) — exact arithmetic, so conservation tests can use
 * {@code assertEquals}, never a tolerance (Topic 11).
 *
 * <p>Balance is guarded by {@link #lock}; every read and write happens while holding it.
 */
public final class Account {

    private final long id;
    private final ReentrantLock lock = new ReentrantLock();
    private long balanceMinor;

    public Account(long id, long openingBalanceMinor) {
        if (openingBalanceMinor < 0) {
            throw new IllegalArgumentException("opening balance must be >= 0");
        }
        this.id = id;
        this.balanceMinor = openingBalanceMinor;
    }

    public long id() {
        return id;
    }

    /** Locked read. */
    public long balance() {
        lock.lock();
        try {
            return balanceMinor;
        } finally {
            lock.unlock();
        }
    }

    ReentrantLock lock() {
        return lock;
    }

    /** Caller must hold {@link #lock}. */
    long balanceLocked() {
        assert lock.isHeldByCurrentThread();
        return balanceMinor;
    }

    /** Caller must hold {@link #lock}. */
    void addLocked(long deltaMinor) {
        assert lock.isHeldByCurrentThread();
        balanceMinor += deltaMinor;
    }
}
