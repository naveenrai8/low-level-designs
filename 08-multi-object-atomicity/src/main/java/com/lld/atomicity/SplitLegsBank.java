package com.lld.atomicity;

import java.util.Collection;

/**
 * BUG: atomic parts, non-atomic whole. The withdraw and the deposit are each done under their
 * own account's lock, one after the other — so there is never a deadlock, and never a moment where
 * both are held. Between the legs the money exists in <em>neither</em> account: an audit then sees
 * the bank short by {@code amount}, and a crash there loses it for good.
 *
 * <p>Same shape in the wild: "decrement inventory, then create order" as two separately locked
 * steps; "debit wallet, then credit merchant" as two DB transactions.
 */
public final class SplitLegsBank implements Bank {

    private final LockGap betweenLegs;

    public SplitLegsBank(LockGap betweenLegs) {
        this.betweenLegs = betweenLegs;
    }

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

    @Override
    public long audit(Collection<Account> accounts) {
        return accounts.stream().mapToLong(Account::balance).sum();
    }
}
