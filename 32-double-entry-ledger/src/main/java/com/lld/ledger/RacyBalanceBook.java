package com.lld.ledger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DELIBERATELY BROKEN. A balance map whose spend does "check the balance, then write the new balance" with no lock between the two steps.
 * <p>Bug class: check-then-act race (also called time-of-check to time-of-use). Two threads both see enough money, both write, and the account is overdrawn although each single call looked correct. The {@link Gap} sits exactly between the check and the write so a test can force the interleaving.
 */
public final class RacyBalanceBook {

    private final Map<String, Long> balances = new ConcurrentHashMap<>();
    private final Gap gap;

    /**
     * Creates the book.
     *
     * @param gap the hook called between the check and the write
     */
    public RacyBalanceBook(Gap gap) {
        this.gap = gap;
    }

    /**
     * Sets an account's balance.
     *
     * @param account the account
     * @param amount the balance
     */
    public void put(String account, long amount) {
        balances.put(account, amount);
    }

    /**
     * Reads a balance.
     *
     * @param account the account
     * @return the balance
     */
    public long get(String account) {
        return balances.get(account);
    }

    /**
     * Spends money with a racy check-then-write.
     *
     * @param account the account
     * @param amount the amount to spend
     * @return true if the check passed and the write happened
     */
    public boolean spend(String account, long amount) {
        if (balances.get(account) < amount) {
            return false;
        }
        gap.at("checked");
        balances.put(account, balances.get(account) - amount);
        return true;
    }
}
