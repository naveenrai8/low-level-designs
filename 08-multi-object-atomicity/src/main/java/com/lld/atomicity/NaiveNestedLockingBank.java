package com.lld.atomicity;

import java.util.Collection;

/**
 * BUG: deadlock. Locks {@code from} then {@code to}. Transfer A→B takes A then waits for B, while
 * B→A takes B then waits for A: each holds what the other needs, forever. Every transfer is
 * individually "correctly locked"; the bug is the <em>order</em>.
 *
 * <p>{@code lockInterruptibly} is used only so tests can break the deadlock and clean up; with
 * {@code synchronized} the threads could never be freed.
 */
public final class NaiveNestedLockingBank implements Bank {

    private final LockGap betweenLocks;

    public NaiveNestedLockingBank(LockGap betweenLocks) {
        this.betweenLocks = betweenLocks;
    }

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

    @Override
    public long audit(Collection<Account> accounts) {
        return accounts.stream().mapToLong(Account::balance).sum();
    }
}
