package com.lld.atomicity;

import java.util.Collection;

/**
 * BUG: deadlock. It locks {@code from} then {@code to}, in whatever order the caller gave.
 * <p>Example: transfer A to B takes A and then waits for B. At the same time transfer B to A takes B and then waits for A. Each holds what the other needs, forever. Every transfer is correctly locked on its own, so the bug is the order.
 * <p>{@code lockInterruptibly} is used only so tests can break the deadlock and clean up. With {@code synchronized} the threads could never be freed.
 */
public final class NaiveNestedLockingBank implements Bank {

    private final LockGap betweenLocks;

    /**
     * Creates the broken bank. The gap runs after the first lock is taken, so a test can make both threads hold one lock each.
     */
    public NaiveNestedLockingBank(LockGap betweenLocks) {
        this.betweenLocks = betweenLocks;
    }

    /**
     * Locks the source, pauses in the gap, locks the destination, then checks and moves the money. Two opposing transfers can deadlock here.
     */
    @Override
    public TransferResult transfer(Account from, Account to, long amountMinor) throws InterruptedException {
        Bank.validate(from, to, amountMinor);
        from.lock().lockInterruptibly();
        try {
            betweenLocks.pause();
            to.lock().lockInterruptibly();
            try {
                if (from.balanceLocked() < amountMinor) {
                    return TransferResult.INSUFFICIENT_FUNDS;
                }
                from.addLocked(-amountMinor);
                to.addLocked(amountMinor);
                return TransferResult.OK;
            } finally {
                to.lock().unlock();
            }
        } finally {
            from.lock().unlock();
        }
    }

    /**
     * Adds up balances one account at a time, each under its own lock. This is not one consistent instant, but it is enough for this bug.
     */
    @Override
    public long audit(Collection<Account> accounts) {
        return accounts.stream().mapToLong(Account::balance).sum();
    }
}
