package com.lld.atomicity;

import java.util.concurrent.locks.ReentrantLock;

/**
 * One bank account. Money is {@code long} minor units (cents), so the maths is exact and conservation tests can use {@code assertEquals} with no tolerance (Topic 11).
 * <p>The balance is guarded by {@link #lock}. Every read and write happens while holding it.
 */
public final class Account {

    private final long id;
    private final ReentrantLock lock = new ReentrantLock();
    private long balanceMinor;

    /**
     * Opens an account with a non-negative starting balance. Throws {@code IllegalArgumentException} if the balance is negative.
     */
    public Account(long id, long openingBalanceMinor) {
        if (openingBalanceMinor < 0) {
            throw new IllegalArgumentException("opening balance must be >= 0");
        }
        this.id = id;
        this.balanceMinor = openingBalanceMinor;
    }

    /**
     * Returns the account's unique, never-changing id. The bank orders its locks by this id.
     */
    public long id() {
        return id;
    }

    /**
     * Reads the balance while holding the account's lock, so the answer is never half-updated.
     */
    public long balance() {
        lock.lock();
        try {
            return balanceMinor;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gives the bank the lock, so it can hold several accounts' locks across one whole transfer.
     */
    ReentrantLock lock() {
        return lock;
    }

    /**
     * Reads the balance without locking. The caller must already hold {@link #lock}, which the assert checks when assertions are on.
     */
    long balanceLocked() {
        assert lock.isHeldByCurrentThread();
        return balanceMinor;
    }

    /**
     * Adds a signed amount to the balance without locking. The caller must already hold {@link #lock}.
     */
    void addLocked(long deltaMinor) {
        assert lock.isHeldByCurrentThread();
        balanceMinor += deltaMinor;
    }
}
