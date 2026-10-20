package com.lld.atomicity;

import java.util.Collection;

public interface Bank {

    /**
     * Moves {@code amountMinor} from {@code from} to {@code to} atomically: no observer may see
     * the money in neither or both accounts, and {@code from} never goes negative.
     */
    TransferResult transfer(Account from, Account to, long amountMinor) throws InterruptedException;

    /** Sum of all balances, as of one consistent instant. */
    long audit(Collection<Account> accounts) throws InterruptedException;

    static void validate(Account from, Account to, long amountMinor) {
        if (from == to) {
            throw new IllegalArgumentException("cannot transfer to the same account");
        }
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amount must be > 0: " + amountMinor);
        }
    }
}
