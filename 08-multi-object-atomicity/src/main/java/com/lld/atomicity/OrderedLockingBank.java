package com.lld.atomicity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * FIX: hold both locks for the whole transfer, always acquired in one global order (ascending
 * account id). A cycle in the wait-for graph needs someone to wait for a lower id while holding a
 * higher one — which this order forbids — so deadlock is impossible.
 *
 * <p>The audit takes <em>every</em> account's lock in the same order, so it sees one consistent
 * instant. That is correct and blocks all transfers while it runs: fine for a demo, wrong for
 * 10M accounts. At scale you'd read from an append-only ledger instead (Topic 32).
 */
public final class OrderedLockingBank implements Bank {

    private static final Comparator<Account> LOCK_ORDER = Comparator.comparingLong(Account::id);

    private final LockGap betweenLocks;

    public OrderedLockingBank(LockGap betweenLocks) {
        this.betweenLocks = betweenLocks;
    }

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
