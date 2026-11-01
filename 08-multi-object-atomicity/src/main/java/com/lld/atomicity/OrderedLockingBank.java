package com.lld.atomicity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * FIX: hold both locks for the whole transfer, always taken in one global order (ascending account id).
 * <p>A deadlock needs a thread that waits for a lower id while holding a higher one. This order forbids that, so no cycle can form.
 * <p>The audit takes every account's lock in the same order, so it sees one consistent instant. That is correct but blocks all transfers while it runs: fine for a demo, wrong for 10M accounts. At scale you would read from an append-only ledger instead (Topic 32).
 */
public final class OrderedLockingBank implements Bank {

    private static final Comparator<Account> LOCK_ORDER = Comparator.comparingLong(Account::id);

    private final LockGap betweenLocks;

    /**
     * Creates the fixed bank. The gap sits between the first and second lock so a test can show the ordered locks do not deadlock.
     */
    public OrderedLockingBank(LockGap betweenLocks) {
        this.betweenLocks = betweenLocks;
    }

    /**
     * Locks the lower id first and the higher id second, then checks the balance and moves the money while holding both. Returns INSUFFICIENT_FUNDS as a value if the source is too low.
     */
    @Override
    public TransferResult transfer(Account from, Account to, long amountMinor) throws InterruptedException {
        Bank.validate(from, to, amountMinor);
        Account first = LOCK_ORDER.compare(from, to) < 0 ? from : to;
        Account second = first == from ? to : from;
        first.lock().lockInterruptibly();
        try {
            betweenLocks.pause();
            second.lock().lockInterruptibly();
            try {
                if (from.balanceLocked() < amountMinor) {
                    return TransferResult.INSUFFICIENT_FUNDS;
                }
                from.addLocked(-amountMinor);
                to.addLocked(amountMinor);
                return TransferResult.OK;
            } finally {
                second.lock().unlock();
            }
        } finally {
            first.lock().unlock();
        }
    }

    /**
     * Locks every account in id order, sums the balances, and unlocks in reverse. If it is interrupted halfway, it still unlocks only the locks it took.
     */
    @Override
    public long audit(Collection<Account> accounts) throws InterruptedException {
        List<Account> ordered = new ArrayList<>(accounts);
        ordered.sort(LOCK_ORDER);
        int locked = 0;
        try {
            for (Account a : ordered) {
                a.lock().lockInterruptibly();
                locked++;
            }
            long total = 0;
            for (Account a : ordered) {
                total += a.balanceLocked();
            }
            return total;
        } finally {
            for (int i = locked - 1; i >= 0; i--) {
                ordered.get(i).lock().unlock();
            }
        }
    }
}
