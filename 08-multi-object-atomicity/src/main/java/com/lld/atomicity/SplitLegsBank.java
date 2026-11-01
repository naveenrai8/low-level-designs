package com.lld.atomicity;

import java.util.Collection;

/**
 * BUG: each part is atomic but the whole is not.
 * <p>Example: moving 300 from A to B. The bank locks A, takes 300, unlocks A. Only then does it lock B and add 300. In between, the 300 is in neither account, so an audit sees the bank 300 short, and a crash there loses the money for good.
 * <p>There is never a deadlock, because two locks are never held together. That is exactly why the in-between state is visible.
 * <p>The same shape in the wild: "decrement inventory, then create order" as two separately locked steps, or "debit wallet, then credit merchant" as two DB transactions.
 */
public final class SplitLegsBank implements Bank {

    private final LockGap betweenLegs;

    /**
     * Creates the broken bank. The gap runs between the two legs, so a test can look at the half-done state.
     */
    public SplitLegsBank(LockGap betweenLegs) {
        this.betweenLegs = betweenLegs;
    }

    /**
     * Withdraws under the source's lock, pauses in the gap while the money is in flight, then deposits under the destination's lock.
     */
    @Override
    public TransferResult transfer(Account from, Account to, long amountMinor) {
        Bank.validate(from, to, amountMinor);
        from.lock().lock();
        try {
            if (from.balanceLocked() < amountMinor) {
                return TransferResult.INSUFFICIENT_FUNDS;
            }
            from.addLocked(-amountMinor);
        } finally {
            from.lock().unlock();
        }
        betweenLegs.pause(); // money is in flight: in neither account
        to.lock().lock();
        try {
            to.addLocked(amountMinor);
        } finally {
            to.lock().unlock();
        }
        return TransferResult.OK;
    }

    /**
     * Adds up balances one account at a time. During a transfer's gap this returns a total that is short by the amount in flight.
     */
    @Override
    public long audit(Collection<Account> accounts) {
        return accounts.stream().mapToLong(Account::balance).sum();
    }
}
