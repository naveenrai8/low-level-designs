package com.lld.atomicity;

import java.util.Collection;

/**
 * Moves money between accounts. The two implementations that are broken and the one that is fixed all share this contract.
 */
public interface Bank {

    /**
     * Moves {@code amountMinor} from {@code from} to {@code to} atomically. No observer may see the money in neither account or in both, and {@code from} never goes negative.
     * <p>Returns INSUFFICIENT_FUNDS as a value when the balance is too low. Throws {@code IllegalArgumentException} for bad input and {@code InterruptedException} if the thread is interrupted while waiting for a lock.
     */
    TransferResult transfer(Account from, Account to, long amountMinor) throws InterruptedException;

    /**
     * Adds up all balances as of one consistent instant.
     */
    long audit(Collection<Account> accounts) throws InterruptedException;

    /**
     * Rejects transfers to the same account and amounts that are zero or negative. Shared so every bank applies the same input rules.
     */
    static void validate(Account from, Account to, long amountMinor) {
        if (from == to) {
            throw new IllegalArgumentException("cannot transfer to the same account");
        }
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amount must be > 0: " + amountMinor);
        }
    }
}
